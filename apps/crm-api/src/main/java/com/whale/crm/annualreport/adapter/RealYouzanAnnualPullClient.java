package com.whale.crm.annualreport.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.adapter.YouzanApiException;
import com.whale.crm.integration.youzan.adapter.YouzanRemoteClient;
import com.whale.crm.integration.youzan.adapter.YouzanTime;
import com.whale.crm.integration.youzan.application.YouzanTokenService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 真实有赞云只读快照拉取客户端。
 * 复用现有 YouzanTokenService / YouzanRemoteClient：
 * - youzan.trades.sold.get (4.0.2) / youzan.trade.get (4.0.2) 拉取订单商品标题、金额与买家标识；
 * - youzan.users.info.query (1.0.0) 与 youzan.scrm.customer.get (3.0.0) 补全真实昵称/姓名、微信 OpenID/UnionID 及手机号。
 * 当租户未开启 youzanRealEnabled 时显式拒绝，绝不伪造真实有赞返回。
 */
@Component
public class RealYouzanAnnualPullClient implements YouzanAnnualPullClient {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter YOUZAN_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> PAID_STATUSES = Set.of(
            "TRADE_PAID", "WAIT_CONFIRM", "WAIT_SELLER_SEND_GOODS", "WAIT_BUYER_CONFIRM_GOODS", "TRADE_SUCCESS");

    private final IntegrationRuntimeConfigService runtimeConfig;
    private final YouzanTokenService tokenService;
    private final YouzanRemoteClient remoteClient;

    public RealYouzanAnnualPullClient(IntegrationRuntimeConfigService runtimeConfig,
                                      YouzanTokenService tokenService,
                                      YouzanRemoteClient remoteClient) {
        this.runtimeConfig = runtimeConfig;
        this.tokenService = tokenService;
        this.remoteClient = remoteClient;
    }

    @Override
    public PulledOrderPage pullOrderPage(long tenantId, String shopId, Instant windowStart, Instant windowEnd,
                                         int pageNo, int pageSize) {
        requireRealEnabled(tenantId);
        Map<String, Object> query = new HashMap<>();
        query.put("page_no", pageNo);
        query.put("page_size", pageSize);
        query.put("start_created", YouzanTime.format(windowStart));
        query.put("end_created", YouzanTime.format(windowEnd));

        JsonNode response = callWithRefresh(tenantId, shopId, "youzan.trades.sold.get", "4.0.2", query);
        JsonNode data = response.path("data");
        long total = data.path("total_results").asLong(0L);
        List<PulledOrder> orders = new ArrayList<>();
        for (JsonNode wrapper : data.path("full_order_info_list")) {
            JsonNode full = wrapper.path("full_order_info");
            if (!full.isObject()) {
                continue;
            }
            String tid = text(full.path("order_info"), "tid");
            if (tid == null) {
                continue;
            }
            JsonNode effectiveFull = needsDetailRefetch(full) ? fetchDetailNode(tenantId, shopId, tid, full) : full;
            PulledOrder parsed = parseFullOrder(effectiveFull);
            if (parsed != null) {
                orders.add(parsed);
            }
        }
        boolean hasMore = pageNo < 100 && (long) pageNo * pageSize < total;
        return new PulledOrderPage(orders, total, hasMore);
    }

    @Override
    public BuyerProfileEnrichment fetchBuyerProfile(long tenantId, String shopId, String yzOpenId,
                                                    Integer fansType, Long fansId, String orderMobile) {
        requireRealEnabled(tenantId);
        String name = null;
        String nickname = null;
        String genderHint = "UNKNOWN";
        String weixinOpenId = null;
        String weixinUnionId = null;
        String mobile = null;
        List<String> warnings = new ArrayList<>();

        // 1. youzan.users.info.query 1.0.0：获取微信 OpenID / UnionID / 昵称 / 手机号
        try {
            Map<String, Object> userQuery = Map.of(
                    "yz_open_id", yzOpenId,
                    "result_type_list", List.of(0, 1, 2, 9)
            );
            JsonNode userResp = callWithRefresh(tenantId, shopId, "youzan.users.info.query", "1.0.0", userQuery);
            JsonNode data = userResp.path("data");
            JsonNode latest = data.path("latest_info");
            nickname = firstNonBlank(nickname, plainText(latest, "nick_name"));
            for (JsonNode item : data.path("user_list")) {
                JsonNode platformInfo = item.path("platform_info");
                if (platformInfo.isObject()) {
                    weixinOpenId = firstNonBlank(weixinOpenId, plainText(platformInfo, "weixin_open_id"));
                    weixinUnionId = firstNonBlank(weixinUnionId, plainText(platformInfo, "union_id"));
                }
                JsonNode wechatInfo = item.path("wechat_info");
                if (wechatInfo.isObject()) {
                    weixinOpenId = firstNonBlank(weixinOpenId, plainText(wechatInfo, "weixin_open_id"));
                    weixinUnionId = firstNonBlank(weixinUnionId, plainText(wechatInfo, "union_id"));
                }
                JsonNode legacyWeixinInfo = item.path("weixin_info");
                if (legacyWeixinInfo.isObject()) {
                    weixinOpenId = firstNonBlank(weixinOpenId, plainText(legacyWeixinInfo, "weixin_open_id"));
                    weixinUnionId = firstNonBlank(weixinUnionId, plainText(legacyWeixinInfo, "union_id"));
                }
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
            JsonNode topWechatInfo = data.path("wechat_info");
            if (topWechatInfo.isObject()) {
                weixinOpenId = firstNonBlank(weixinOpenId, plainText(topWechatInfo, "weixin_open_id"));
                weixinUnionId = firstNonBlank(weixinUnionId, plainText(topWechatInfo, "union_id"));
            }
            JsonNode topPlatformInfo = data.path("platform_info");
            if (topPlatformInfo.isObject()) {
                weixinOpenId = firstNonBlank(weixinOpenId, plainText(topPlatformInfo, "weixin_open_id"));
                weixinUnionId = firstNonBlank(weixinUnionId, plainText(topPlatformInfo, "union_id"));
            }
        } catch (BusinessException ex) {
            warnings.add("youzan.users.info.query 未开通或暂不可用");
        }

        // 2. youzan.scrm.customer.get 3.0.0：按明文手机号或订单 fans_type/fans_id 获取有赞 SCRM 客户姓名、手机号与性别
        List<Map<String, Object>> scrmQueries = new ArrayList<>(2);
        String lookupMobile = firstNonBlank(mobile, sanitizePlainMobile(orderMobile));
        if (lookupMobile != null) {
            scrmQueries.add(Map.of("mobile", lookupMobile, "fans_type", 0, "fans_id", 0));
        }
        if (fansType != null && fansType > 0 && fansId != null && fansId > 0) {
            scrmQueries.add(Map.of("fans_type", fansType, "fans_id", fansId));
        }
        if (!scrmQueries.isEmpty()) {
            boolean anySucceeded = false;
            boolean anyFailed = false;
            for (Map<String, Object> scrmQuery : scrmQueries) {
                try {
                    JsonNode scrmResp = callWithRefresh(
                            tenantId, shopId, "youzan.scrm.customer.get", "3.0.0", scrmQuery);
                    anySucceeded = true;
                    JsonNode data = scrmResp.path("data");
                    if (data.isObject() && !data.isEmpty()) {
                        String scrmName = plainText(data, "name");
                        String scrmMobile = sanitizePlainMobile(text(data, "mobile"));
                        String scrmGender = parseGender(data.path("gender").asInt(0));
                        name = firstNonBlank(name, scrmName);
                        mobile = firstNonBlank(mobile, scrmMobile);
                        if ("UNKNOWN".equals(genderHint)) {
                            genderHint = scrmGender;
                        }
                        if (scrmName != null || scrmMobile != null || !"UNKNOWN".equals(scrmGender)) {
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
        return new BuyerProfileEnrichment(name, nickname, genderHint, weixinOpenId, weixinUnionId, mobile, warning);
    }

    private void requireRealEnabled(long tenantId) {
        if (!runtimeConfig.youzanRealEnabled(tenantId)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "当前未开启真实有赞连接，请先在「有赞接入」配置店铺授权并开启真实同步开关");
        }
    }

    private JsonNode fetchDetailNode(long tenantId, String shopId, String tid, JsonNode fallback) {
        try {
            JsonNode resp = callWithRefresh(tenantId, shopId, "youzan.trade.get", "4.0.2", Map.of("tid", tid));
            JsonNode detail = resp.path("data").path("full_order_info");
            return detail.isObject() ? detail : fallback;
        } catch (BusinessException ex) {
            return fallback;
        }
    }

    private boolean needsDetailRefetch(JsonNode full) {
        JsonNode orders = full.path("orders");
        if (!orders.isArray() || orders.isEmpty()) {
            return true;
        }
        return text(orders.get(0), "title") == null || text(full.path("buyer_info"), "yz_open_id") == null;
    }

    private PulledOrder parseFullOrder(JsonNode full) {
        JsonNode info = full.path("order_info");
        JsonNode pay = full.path("pay_info");
        JsonNode buyer = full.path("buyer_info");
        JsonNode address = full.path("address_info");

        String tid = text(info, "tid");
        String yzOpenId = text(buyer, "yz_open_id");
        if (tid == null || yzOpenId == null) {
            return null;
        }
        String status = firstNonBlank(text(info, "status"), "UNKNOWN");
        boolean paid = PAID_STATUSES.contains(status);
        Instant orderedAt = parseTime(text(info, "created"));
        Instant paidAt = parseTime(firstNonBlank(text(info, "pay_time"), text(pay, "pay_time")));
        long paymentFen = moneyToFen(text(pay, "payment"));

        long refundedFen = 0L;
        List<PulledOrderItem> items = new ArrayList<>();
        String titleSummary = null;
        for (JsonNode item : full.path("orders")) {
            String itemId = firstNonBlank(text(item, "item_id"), "0");
            String skuId = firstNonBlank(text(item, "sku_id"), "0");
            String title = firstNonBlank(text(item, "title"), "有赞甄选商品");
            String skuProps = text(item, "sku_properties_name");
            int num = Math.max(1, item.path("num").asInt(1));
            long priceFen = moneyToFen(firstNonBlank(text(item, "payment"), text(item, "price")));
            if (titleSummary == null) {
                titleSummary = title;
            }
            items.add(new PulledOrderItem(itemId, skuId, title, skuProps, num, priceFen));
        }
        for (JsonNode refund : full.path("refund_order")) {
            if ("SUCCESS".equalsIgnoreCase(text(refund, "refund_state"))
                    || "20".equals(text(refund, "refund_state"))) {
                refundedFen += moneyToFen(text(refund, "refund_fee"));
            }
        }
        if (titleSummary == null) {
            titleSummary = "鲸选有赞甄选订单";
        }
        JsonNode orderExtra = info.path("order_extra");
        return new PulledOrder(
                tid,
                yzOpenId,
                status,
                paid,
                orderedAt,
                paidAt,
                paymentFen,
                refundedFen,
                firstNonBlank(plainText(buyer, "fans_nickname"), plainText(orderExtra, "buyer_name")),
                plainText(buyer, "buyer_phone"),
                plainText(address, "receiver_name"),
                titleSummary,
                items,
                positiveInt(buyer, "fans_type"),
                positiveLong(buyer, "fans_id")
        );
    }

    private JsonNode callWithRefresh(long tenantId, String shopId, String apiName, String version,
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

    private static long moneyToFen(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, new BigDecimal(raw.trim()).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact());
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    private static Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), YOUZAN_TIME_FMT).atZone(SHANGHAI).toInstant();
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
        return YouzanAnnualPullClient.stripCiphertext(text(node, field));
    }

    private static String sanitizePlainMobile(String raw) {
        String plain = YouzanAnnualPullClient.stripCiphertext(raw);
        if (plain == null) {
            return null;
        }
        String compact = plain.replaceAll("[\\s-]+", "");
        return compact.matches("^\\+?\\d{5,15}$") ? compact : null;
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
