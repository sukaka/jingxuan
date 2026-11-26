package com.whale.crm.integration.youzan.customer.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.integration.youzan.adapter.YouzanApiException;
import com.whale.crm.integration.youzan.adapter.YouzanRemoteClient;
import com.whale.crm.integration.youzan.adapter.YouzanTime;
import com.whale.crm.integration.youzan.application.YouzanTokenService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实有赞云官方客户拉取适配器：
 * - SCRM_LIST 模式：调用 youzan.scrm.customer.list（默认 1.0.0，分页参数 page_no/page_size，按创建时间降序）；
 * - ORDER_BUYERS 模式：复用已实测验证的 youzan.trades.sold.get (4.0.2) / youzan.trade.get (4.0.2)；
 * - 资料补全：复用已实测验证的 youzan.users.info.query (1.0.0) 与 youzan.scrm.customer.get (3.0.0)。
 */
@Component
public class OfficialYouzanCustomerPullClient implements YouzanCustomerPullClient {

    public static final String ADAPTER_NAME = "YOUZAN_OFFICIAL";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter YOUZAN_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int SCRM_MAX_PAGES = 500;
    private static final long SCRM_MAX_TOTAL = 10_000L;
    private static final int SCRM_MAX_PAGE_SIZE = 50;
    private static final int ORDER_MAX_PAGES = 100;

    private final YouzanTokenService tokenService;
    private final YouzanRemoteClient remoteClient;
    private final String scrmListVersion;
    private final String tradeDetailVersion;
    private final long minIntervalMs;
    private final Map<String, Object> rateLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> nextCallAt = new ConcurrentHashMap<>();

    @Autowired
    public OfficialYouzanCustomerPullClient(
            YouzanTokenService tokenService,
            YouzanRemoteClient remoteClient,
            @Value("${YOUZAN_SCRM_CUSTOMER_LIST_VERSION:1.0.0}") String scrmListVersion,
            @Value("${YOUZAN_TRADE_DETAIL_VERSION:4.0.2}") String tradeDetailVersion,
            @Value("${YOUZAN_API_MIN_INTERVAL_MS:100}") long minIntervalMs) {
        this.tokenService = tokenService;
        this.remoteClient = remoteClient;
        this.scrmListVersion = scrmListVersion == null || scrmListVersion.isBlank()
                ? "1.0.0" : scrmListVersion.trim();
        this.tradeDetailVersion = tradeDetailVersion == null || tradeDetailVersion.isBlank()
                ? "4.0.2" : tradeDetailVersion.trim();
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }

    @Override
    public String name() {
        return ADAPTER_NAME;
    }

    @Override
    public boolean officialEnabled() {
        return true;
    }

    @Override
    public YouzanCustomerPage pullScrmPage(long tenantId, String shopId, Instant createdStart, Instant createdEnd,
                                           int pageNo, int pageSize) {
        int effectivePageSize = Math.min(SCRM_MAX_PAGE_SIZE, Math.max(1, pageSize));
        Map<String, Object> body = new HashMap<>();
        body.put("page_no", pageNo);
        body.put("page_size", effectivePageSize);
        if (createdStart != null) {
            body.put("created_at_start", createdStart.getEpochSecond());
        }
        if (createdEnd != null) {
            body.put("created_at_end", createdEnd.getEpochSecond());
        }

        JsonNode response = callScrmList(tenantId, shopId, body);
        JsonNode data = response.path("data");
        long total = firstNonNegativeLong(data, "total", "total_results", "total_count");
        long maxTotal = Math.min(SCRM_MAX_TOTAL, (long) SCRM_MAX_PAGES * effectivePageSize);
        if (total >= maxTotal) {
            throw new YouzanCustomerWindowTooLargeException(total);
        }

        JsonNode listNode = data.path("record_list");
        if (!listNode.isArray()) {
            listNode = data.path("items");
        }
        List<YouzanPulledCustomer> items = new ArrayList<>();
        if (listNode.isArray()) {
            for (JsonNode item : listNode) {
                items.add(parseScrmCustomer(item));
            }
        }
        boolean hasMore = total > 0
                ? pageNo < SCRM_MAX_PAGES && (long) pageNo * effectivePageSize < total
                : pageNo < SCRM_MAX_PAGES && items.size() >= effectivePageSize;
        return new YouzanCustomerPage(items, total, hasMore, List.of());
    }

    @Override
    public YouzanCustomerPage pullOrderBuyersPage(long tenantId, String shopId, Instant windowStart, Instant windowEnd,
                                                  int pageNo, int pageSize) {
        Map<String, Object> query = new HashMap<>();
        query.put("page_no", pageNo);
        query.put("page_size", pageSize);
        query.put("start_created", YouzanTime.format(windowStart));
        query.put("end_created", YouzanTime.format(windowEnd));

        JsonNode response = callApi(tenantId, shopId, "youzan.trades.sold.get", "4.0.2", query);
        JsonNode data = response.path("data");
        long total = data.path("total_results").asLong(0L);
        if (total > (long) ORDER_MAX_PAGES * pageSize) {
            throw new YouzanCustomerWindowTooLargeException(total);
        }

        List<YouzanPulledCustomer> items = new ArrayList<>();
        for (JsonNode wrapper : data.path("full_order_info_list")) {
            JsonNode full = wrapper.path("full_order_info");
            if (!full.isObject()) {
                continue;
            }
            String tid = text(full.path("order_info"), "tid");
            if (text(full.path("buyer_info"), "yz_open_id") == null && tid != null) {
                full = fetchOrderDetail(tenantId, shopId, tid, full);
            }
            items.add(parseOrderBuyer(full));
        }
        boolean hasMore = pageNo < ORDER_MAX_PAGES && (long) pageNo * pageSize < total;
        return new YouzanCustomerPage(items, total, hasMore, List.of());
    }

    @Override
    public YouzanCustomerEnrichment enrichCustomer(long tenantId, String shopId, String yzOpenId,
                                                   Integer fansType, Long fansId, String plainMobile) {
        if (yzOpenId == null || yzOpenId.isBlank()) {
            return YouzanCustomerEnrichment.empty(null);
        }
        String name = null;
        String nickname = null;
        String mobile = sanitizePlainMobile(plainMobile);
        String genderHint = "UNKNOWN";
        LocalDate birthday = null;
        List<String> warnings = new ArrayList<>();

        // 1. youzan.users.info.query (1.0.0)：实测 result_type_list=[0, 1, 2, 9]
        try {
            Map<String, Object> userQuery = Map.of(
                    "yz_open_id", yzOpenId,
                    "result_type_list", List.of(0, 1, 2, 9)
            );
            JsonNode userResp = callApi(tenantId, shopId, "youzan.users.info.query", "1.0.0", userQuery);
            JsonNode data = userResp.path("data");
            JsonNode latest = data.path("latest_info");
            nickname = firstNonBlank(nickname, plainText(latest, "nick_name"));
            for (JsonNode item : data.path("user_list")) {
                JsonNode mobileInfo = item.path("mobile_info");
                if (mobileInfo.isObject()) {
                    mobile = firstNonBlank(mobile, sanitizePlainMobile(text(mobileInfo, "mobile")));
                }
                JsonNode primitive = item.path("primitive_info");
                if (primitive.isObject()) {
                    nickname = firstNonBlank(nickname, plainText(primitive, "nick_name"));
                    if ("UNKNOWN".equals(genderHint)) {
                        genderHint = parseGender(primitive.path("gender").asInt(0));
                    }
                }
            }
        } catch (BusinessException ex) {
            warnings.add("youzan.users.info.query 未开通或暂不可用");
        }

        // 2. youzan.scrm.customer.get (3.0.0)：按明文手机号或 fans_type + fans_id 获取真实客户姓名与生日
        List<Map<String, Object>> scrmQueries = new ArrayList<>(2);
        if (mobile != null) {
            scrmQueries.add(Map.of("mobile", mobile, "fans_type", 0, "fans_id", 0));
        }
        if (fansType != null && fansType > 0 && fansId != null && fansId > 0) {
            scrmQueries.add(Map.of("fans_type", fansType, "fans_id", fansId));
        }
        if (!scrmQueries.isEmpty()) {
            boolean anySucceeded = false;
            boolean anyFailed = false;
            for (Map<String, Object> scrmQuery : scrmQueries) {
                try {
                    JsonNode scrmResp = callApi(tenantId, shopId, "youzan.scrm.customer.get", "3.0.0", scrmQuery);
                    anySucceeded = true;
                    JsonNode data = scrmResp.path("data");
                    if (data.isObject() && !data.isEmpty()) {
                        String scrmName = plainText(data, "name");
                        String scrmMobile = sanitizePlainMobile(text(data, "mobile"));
                        String scrmGender = parseGender(data.path("gender").asInt(0));
                        LocalDate scrmBirthday = parseBirthday(text(data, "birthday"));
                        name = firstNonBlank(name, scrmName);
                        mobile = firstNonBlank(mobile, scrmMobile);
                        if ("UNKNOWN".equals(genderHint)) {
                            genderHint = scrmGender;
                        }
                        if (birthday == null) {
                            birthday = scrmBirthday;
                        }
                        if (scrmName != null || scrmMobile != null || scrmBirthday != null) {
                            break;
                        }
                    }
                } catch (BusinessException ex) {
                    anyFailed = true;
                }
            }
            if (!anySucceeded && anyFailed) {
                warnings.add("youzan.scrm.customer.get 未开通或暂不可用");
            }
        }

        String warning = warnings.isEmpty() ? null : String.join("；", warnings);
        return new YouzanCustomerEnrichment(name, nickname, mobile, genderHint, birthday, warning);
    }

    private JsonNode callScrmList(long tenantId, String shopId, Map<String, Object> body) {
        try {
            return callApi(tenantId, shopId, "youzan.scrm.customer.list", scrmListVersion, body);
        } catch (YouzanApiException ex) {
            throw ex;
        } catch (BusinessException ex) {
            String message = ex.getMessage();
            if (message != null && message.contains("142710032")) {
                throw new YouzanCustomerWindowTooLargeException(SCRM_MAX_TOTAL + 1L);
            }
            if (message != null && (message.contains("4204") || message.contains("4005"))) {
                throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                        "有赞全量客户列表接口（youzan.scrm.customer.list/" + scrmListVersion
                                + "）未开通权限或暂不可用，请确认有赞云已开通「店铺客户信息同步」能力包，或切换为「按有赞订单买家拉取」模式（"
                                + safeReason(message) + "）");
            }
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                    "有赞全量客户列表接口（youzan.scrm.customer.list/" + scrmListVersion
                            + "）调用失败，请稍后重试或切换为「按有赞订单买家拉取」模式（"
                            + safeReason(message) + "）");
        }
    }

    private JsonNode fetchOrderDetail(long tenantId, String shopId, String tid, JsonNode fallback) {
        try {
            JsonNode resp = callApi(tenantId, shopId, "youzan.trade.get", tradeDetailVersion, Map.of("tid", tid));
            JsonNode detail = resp.path("data").path("full_order_info");
            return detail.isObject() ? detail : fallback;
        } catch (BusinessException ex) {
            return fallback;
        }
    }

    private YouzanPulledCustomer parseScrmCustomer(JsonNode item) {
        String yzOpenId = firstNonBlank(text(item, "yz_open_id"), text(item, "yz_open_id_str"));
        String mobile = sanitizePlainMobile(text(item, "mobile"));
        String showName = nonMobileDisplayName(plainText(item, "show_name"), mobile);
        String name = firstNonBlank(plainText(item, "name"), showName);
        String nickname = firstNonBlank(plainText(item, "nick_name"), plainText(item, "nickname"), showName);
        String genderHint = parseGender(item.path("gender").asInt(0));
        LocalDate birthday = parseBirthday(text(item, "birthday"));
        Instant createdAt = parseFlexibleTime(item.path("created_at"));
        JsonNode fansInfo = item.path("fans_info");
        Integer fansType = firstPositiveInt(positiveInt(item, "fans_type"), positiveInt(fansInfo, "fans_type"));
        Long fansId = firstPositiveLong(positiveLong(item, "fans_id"), positiveLong(fansInfo, "fans_id"));
        String remark = plainText(item, "remark");
        return new YouzanPulledCustomer(yzOpenId, name, nickname, mobile, genderHint, birthday, createdAt,
                fansType, fansId, remark);
    }

    private YouzanPulledCustomer parseOrderBuyer(JsonNode full) {
        JsonNode info = full.path("order_info");
        JsonNode buyer = full.path("buyer_info");
        JsonNode address = full.path("address_info");
        JsonNode orderExtra = info.path("order_extra");
        String yzOpenId = text(buyer, "yz_open_id");
        String name = firstNonBlank(plainText(orderExtra, "buyer_name"), plainText(address, "receiver_name"));
        String nickname = firstNonBlank(plainText(buyer, "fans_nickname"), plainText(orderExtra, "buyer_name"));
        String mobile = firstNonBlank(
                sanitizePlainMobile(text(buyer, "buyer_phone")),
                sanitizePlainMobile(text(address, "receiver_tel")));
        Instant createdAt = parseFlexibleTime(info.path("created"));
        Integer fansType = positiveInt(buyer, "fans_type");
        Long fansId = positiveLong(buyer, "fans_id");
        return new YouzanPulledCustomer(yzOpenId, name, nickname, mobile, "UNKNOWN", null, createdAt,
                fansType, fansId, null);
    }

    private JsonNode callApi(long tenantId, String shopId, String apiName, String version, Map<String, Object> body) {
        for (int attempt = 0; attempt < 3; attempt++) {
            throttle(shopId);
            try {
                return callWithTokenRefresh(tenantId, shopId, apiName, version, body);
            } catch (YouzanApiException ex) {
                if (!ex.rateLimited() || attempt == 2) {
                    throw ex;
                }
                long backoff = ex.retryAfter() == null ? 250L * (1L << attempt)
                        : Math.min(60000L, ex.retryAfter().toMillis());
                pause(backoff);
            }
        }
        throw new IllegalStateException("不可达的有赞客户调用状态");
    }

    private JsonNode callWithTokenRefresh(long tenantId, String shopId, String apiName, String version,
                                          Map<String, Object> body) {
        try {
            return remoteClient.callApi(tokenService.accessToken(tenantId, shopId), apiName, version, body);
        } catch (YouzanApiException ex) {
            if (!ex.authError()) {
                throw ex;
            }
            String refreshed = tokenService.refreshAccessToken(tenantId, shopId);
            return remoteClient.callApi(refreshed, apiName, version, body);
        }
    }

    private void throttle(String shopId) {
        Object lock = rateLocks.computeIfAbsent(shopId, key -> new Object());
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long scheduled = Math.max(now, nextCallAt.getOrDefault(shopId, now));
            long wait = scheduled - now;
            nextCallAt.put(shopId, scheduled + minIntervalMs);
            if (wait > 0) {
                pause(wait);
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "有赞客户接口退避等待已中断");
        }
    }

    private static Instant parseFlexibleTime(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            long val = node.asLong(0L);
            if (val <= 0L) {
                return null;
            }
            return val > 10_000_000_000L ? Instant.ofEpochMilli(val) : Instant.ofEpochSecond(val);
        }
        String raw = node.asText("").trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.matches("^\\d{10,13}$")) {
            long val = Long.parseLong(raw);
            return raw.length() >= 13 ? Instant.ofEpochMilli(val) : Instant.ofEpochSecond(val);
        }
        try {
            return LocalDateTime.parse(raw, YOUZAN_TIME_FMT).atZone(SHANGHAI).toInstant();
        } catch (RuntimeException ex) {
            try {
                return Instant.parse(raw);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static LocalDate parseBirthday(String raw) {
        String clean = YouzanCustomerPullClient.stripCiphertext(raw);
        if (clean == null) {
            return null;
        }
        String candidate = clean.length() >= 10 ? clean.substring(0, 10) : clean;
        try {
            LocalDate parsed = LocalDate.parse(candidate, DATE_FMT);
            return parsed.getYear() >= 1900 && !parsed.isAfter(LocalDate.now(SHANGHAI)) ? parsed : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String parseGender(int code) {
        if (code == 1) {
            return "MALE";
        }
        if (code == 2) {
            return "FEMALE";
        }
        return "UNKNOWN";
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    private static String plainText(JsonNode node, String field) {
        return YouzanCustomerPullClient.stripCiphertext(text(node, field));
    }

    private static String sanitizePlainMobile(String raw) {
        String plain = YouzanCustomerPullClient.stripCiphertext(raw);
        if (plain == null) {
            return null;
        }
        String compact = plain.replaceAll("[\\s-]+", "");
        return compact.matches("^\\+?\\d{5,15}$") ? compact : null;
    }

    private static String nonMobileDisplayName(String candidate, String mobile) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String compact = candidate.replaceAll("[\\s-]+", "");
        if (mobile != null && compact.equals(mobile)) {
            return null;
        }
        if (compact.matches("^(\\+?86)?1[3-9]\\d{9}$")) {
            return null;
        }
        return candidate;
    }

    private static long firstNonNegativeLong(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode val = node.path(field);
            if (!val.isMissingNode() && !val.isNull()) {
                long parsed = val.asLong(-1L);
                if (parsed >= 0L) {
                    return parsed;
                }
            }
        }
        return -1L;
    }

    private static Integer positiveInt(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            int parsed = value.canConvertToInt() ? value.intValue() : Integer.parseInt(value.asText().trim());
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Long positiveLong(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            long parsed = value.canConvertToLong() ? value.longValue() : Long.parseLong(value.asText().trim());
            return parsed > 0L ? parsed : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Integer firstPositiveInt(Integer... values) {
        for (Integer v : values) {
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    private static Long firstPositiveLong(Long... values) {
        for (Long v : values) {
            if (v != null && v > 0L) {
                return v;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String safeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return "网关错误";
        }
        return raw.length() > 160 ? raw.substring(0, 160) : raw;
    }
}
