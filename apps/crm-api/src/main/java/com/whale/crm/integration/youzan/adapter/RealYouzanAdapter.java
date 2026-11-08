package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.youzan.application.YouzanTokenService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class RealYouzanAdapter implements YouzanAdapter {
    private static final Duration MAX_WINDOW = Duration.ofDays(90);
    private final YouzanTokenService tokenService;
    private final YouzanRemoteClient remoteClient;
    private final YouzanOrderMapper orderMapper;
    private final ObjectMapper objectMapper;
    private final String detailVersion;
    private final long minIntervalMs;
    private final Map<String, Object> rateLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> nextCallAt = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RealYouzanAdapter(YouzanTokenService tokenService, YouzanRemoteClient remoteClient,
                             YouzanOrderMapper orderMapper, ObjectMapper objectMapper,
                             @Value("${YOUZAN_TRADE_DETAIL_VERSION:4.0.2}") String detailVersion,
                             @Value("${YOUZAN_API_MIN_INTERVAL_MS:100}") long minIntervalMs) {
        this.tokenService = tokenService;
        this.remoteClient = remoteClient;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
        this.detailVersion = detailVersion;
        this.minIntervalMs = Math.max(0, minIntervalMs);
    }

    RealYouzanAdapter(YouzanTokenService tokenService, YouzanRemoteClient remoteClient,
                      YouzanOrderMapper orderMapper, ObjectMapper objectMapper, String detailVersion) {
        this(tokenService, remoteClient, orderMapper, objectMapper, detailVersion, 100);
    }

    @Override
    public String name() {
        return "YOUZAN_OFFICIAL";
    }

    @Override
    public YouzanVerification verifyWebhook(String rawBody, YouzanSignature signature) {
        return new YouzanVerification(false, "OFFICIAL_SIGNATURE_REQUIRED");
    }

    @Override
    public YouzanPullPage pullOrders(long tenantId, String shopId, String cursor, int limit) {
        YouzanSyncCursorValue position = readCursor(cursor);
        validateWindow(position);
        Map<String, Object> query = new HashMap<>();
        query.put("page_no", position.pageNo());
        query.put("page_size", limit);
        if ("UPDATED".equals(position.queryType())) {
            query.put("start_update", YouzanTime.format(position.windowStart()));
            query.put("end_update", YouzanTime.format(position.windowEnd()));
        } else {
            query.put("start_created", YouzanTime.format(position.windowStart()));
            query.put("end_created", YouzanTime.format(position.windowEnd()));
        }
        JsonNode response = callApi(tenantId, shopId, "youzan.trades.sold.get", "4.0.2", query);
        JsonNode data = response.path("data");
        long total = data.path("total_results").asLong(0);
        List<YouzanOrderEvent> events = new ArrayList<>();
        Map<String, String> rawPayloads = new HashMap<>();
        for (JsonNode wrapper : data.path("full_order_info_list")) {
            String tid = wrapper.path("full_order_info").path("order_info").path("tid").asText();
            if (tid.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞订单列表缺少订单号");
            }
            YouzanOrderEnvelope detail = fetchOrder(tenantId, shopId, null, tid);
            events.add(detail.event());
            rawPayloads.put(detail.event().eventId(), detail.providerPayload());
        }
        if (position.pageNo() == 100 && (long) position.pageNo() * limit < total) {
            throw new YouzanWindowTooLargeException();
        }
        boolean hasMore = position.pageNo() < 100 && (long) position.pageNo() * limit < total;
        YouzanSyncCursorValue next = new YouzanSyncCursorValue(position.queryType(), position.windowStart(),
                position.windowEnd(), position.finalEnd(), hasMore ? position.pageNo() + 1 : position.pageNo());
        return new YouzanPullPage(events, writeCursor(next), hasMore, rawPayloads);
    }

    @Override
    public YouzanOrderEnvelope fetchOrder(long tenantId, String shopId, String eventId, String orderNo) {
        JsonNode detail = callApi(tenantId, shopId, "youzan.trade.get", detailVersion, Map.of("tid", orderNo));
        return new YouzanOrderEnvelope(orderMapper.toOrderEvent(shopId, eventId, detail), detail.toString());
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
        throw new IllegalStateException("不可达的有赞调用状态");
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
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "有赞接口退避等待已中断");
        }
    }

    private YouzanSyncCursorValue readCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            Instant end = Instant.now();
            return YouzanSyncCursorValue.created(end.minus(Duration.ofDays(7)), end);
        }
        try {
            return objectMapper.readValue(cursor, YouzanSyncCursorValue.class);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞同步游标无效");
        }
    }

    private String writeCursor(YouzanSyncCursorValue cursor) {
        try {
            return objectMapper.writeValueAsString(cursor);
        } catch (Exception ex) {
            throw new IllegalStateException("有赞同步游标序列化失败", ex);
        }
    }

    private static void validateWindow(YouzanSyncCursorValue cursor) {
        if (cursor.windowStart() == null || cursor.windowEnd() == null || cursor.finalEnd() == null
                || !cursor.windowEnd().isAfter(cursor.windowStart())
                || Duration.between(cursor.windowStart(), cursor.windowEnd()).compareTo(MAX_WINDOW) > 0
                || cursor.pageNo() < 1 || cursor.pageNo() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞同步时间窗或页码超出官方限制");
        }
    }
}
