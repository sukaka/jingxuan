package com.whale.crm.integration.youzan.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

@Component
@Profile("local")
public class MockYouzanAdapter implements YouzanAdapter {
    private static final long WINDOW_SECONDS = 300;
    private final String mockSecret;

    public MockYouzanAdapter(@Value("${YOUZAN_MOCK_SECRET:dev-only-youzan-mock-secret}") String mockSecret) {
        this.mockSecret = mockSecret;
    }

    @Override
    public String name() {
        return "MOCK_DEV_ONLY";
    }

    @Override
    public YouzanVerification verifyWebhook(String rawBody, YouzanSignature signature) {
        if (signature == null || signature.signature() == null || signature.signature().isBlank()
                || signature.timestamp() == null) {
            return new YouzanVerification(false, "MOCK_SIGNATURE_MISSING");
        }
        long now = Instant.now().getEpochSecond();
        if (signature.timestamp() < now - WINDOW_SECONDS || signature.timestamp() > now + WINDOW_SECONDS) {
            return new YouzanVerification(false, "MOCK_SIGNATURE_EXPIRED");
        }
        String expected = signMock(rawBody, signature.timestamp());
        boolean matches = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.signature().getBytes(StandardCharsets.UTF_8));
        return matches ? new YouzanVerification(true, null) : new YouzanVerification(false, "MOCK_SIGNATURE_INVALID");
    }

    public String signMock(String rawBody, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(mockSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + rawBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Mock签名计算失败", ex);
        }
    }

    @Override
    public YouzanPullPage pullOrders(long tenantId, String shopId, String cursor, int limit) {
        if (!"MOCK-SHOP-001".equals(shopId)) {
            return new YouzanPullPage(List.of(), cursor, false);
        }
        int from = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
        List<YouzanOrderEvent> fixtures = List.of(paidEvent(), missingSkuEvent());
        if (from >= fixtures.size()) {
            return new YouzanPullPage(List.of(), Integer.toString(from), false);
        }
        int to = Math.min(from + Math.max(1, Math.min(limit, 100)), fixtures.size());
        boolean hasMore = to < fixtures.size();
        return new YouzanPullPage(fixtures.subList(from, to), Integer.toString(to), hasMore);
    }

    @Override
    public YouzanOrderEnvelope fetchOrder(long tenantId, String shopId, String eventId, String orderNo) {
        if (!"MOCK-SHOP-001".equals(shopId)) {
            throw new com.whale.crm.platform.web.BusinessException(
                    com.whale.crm.platform.web.ErrorCode.NOT_FOUND, "模拟演示环境仅支持 MOCK-SHOP-001 店铺按单号拉取");
        }
        String cleanTid = orderNo == null ? "" : orderNo.strip();
        if (cleanTid.isEmpty()) {
            throw new com.whale.crm.platform.web.BusinessException(
                    com.whale.crm.platform.web.ErrorCode.VALIDATION_FAILED, "有赞订单号不能为空");
        }
        Instant paidAt = Instant.parse("2026-11-11T07:40:48Z");
        Instant updatedAt = Instant.parse("2026-11-11T12:42:43Z");
        String actualEventId = eventId == null || eventId.isBlank()
                ? "MOCK-PULL:" + cleanTid + ":" + updatedAt.toEpochMilli() : eventId;
        YouzanOrderEvent event = new YouzanOrderEvent(
                actualEventId, "ORDER_PAID", shopId, cleanTid,
                "YZ-DEMO-CUSTOMER-1010", "MOCK-TRADE-" + cleanTid, "WAIT_BUYER_CONFIRM_GOODS", 499900L,
                paidAt, updatedAt, "REGULAR", null, null, "PLATFORM", LocalDate.parse("2027-12-31"),
                List.of(new YouzanOrderLine("4898748343:15042235417", 1)));
        String escapedTid = cleanTid.replace("\\", "\\\\").replace("\"", "\\\"");
        String providerPayload = (
                "{\"code\":200,\"success\":true,\"message\":\"successful\",\"data\":{\"full_order_info\":{"
                + "\"remark_info\":{\"buyer_message\":\"\",\"star\":0,"
                + "\"trade_memo\":\"业绩：模拟顾问，渠道：koc，类型：新客，来源：模拟达人，账号：演示管家，到院：演示合作机构  可延期核销申请半个月\"},"
                + "\"pay_info\":{\"pay_way_desc\":\"微信支付\",\"payment\":\"4999.00\","
                + "\"transaction\":[\"MOCK-TRADE-" + escapedTid + "\"],\"phase_payments\":[]},"
                + "\"buyer_info\":{\"yz_open_id\":\"YZ-DEMO-CUSTOMER-1010\","
                + "\"buyer_phone\":\"$MOCK_CIPHER$1$\",\"fans_nickname\":\"$MOCK_CIPHER$1$\"},"
                + "\"orders\":[{\"item_id\":4898748343,\"sku_id\":15042235417,\"num\":1,"
                + "\"price\":\"4999.00\",\"payment\":\"4999.00\",\"title\":\"模拟年度甄选卡\","
                + "\"sku_properties_name\":\"[{\\\"k\\\":\\\"城市\\\",\\\"v\\\":\\\"广州\\\"}]\","
                + "\"buyer_messages\":\"{\\\"姓名\\\":\\\"测试客户\\\",\\\"手机号\\\":\\\"13800001234\\\"}\"}],"
                + "\"order_info\":{\"tid\":\"" + escapedTid + "\",\"status\":\"WAIT_BUYER_CONFIRM_GOODS\","
                + "\"status_str\":\"已发货\",\"pay_type_desc\":\"微信支付\","
                + "\"created\":\"2026-11-11 15:40:31\",\"pay_time\":\"2026-11-11 15:40:48\","
                + "\"consign_time\":\"2026-11-11 15:40:49\",\"update_time\":\"2026-11-11 20:42:43\"}"
                + "}}}");
        return new YouzanOrderEnvelope(event, providerPayload);
    }

    public static YouzanOrderEvent paidEvent() {
        return new YouzanOrderEvent("YZ-MOCK-PAID-001", "ORDER_PAID", "MOCK-SHOP-001", "YZ-MOCK-PAID-001",
                "YZ-DEMO-CUSTOMER-1010", "YZ-MOCK-TRADE-001", "PAID", 362200L,
                Instant.parse("2026-10-24T03:00:00Z"), Instant.parse("2026-10-24T03:00:00Z"),
                "REGULAR", 1L, 1L, "PLATFORM", LocalDate.parse("2027-12-31"),
                List.of(new YouzanOrderLine("YZ-SKU-301", 2)));
    }

    public static YouzanOrderEvent missingSkuEvent() {
        return new YouzanOrderEvent("YZ-MOCK-MISSING-001", "ORDER_PAID", "MOCK-SHOP-001", "YZ-MOCK-MISSING-001",
                "YZ-DEMO-CUSTOMER-1010", "YZ-MOCK-TRADE-002", "PAID", 181100L,
                Instant.parse("2026-10-24T04:00:00Z"), Instant.parse("2026-10-24T04:00:00Z"),
                "REGULAR", 1L, 1L, "PLATFORM", LocalDate.parse("2027-12-31"),
                List.of(new YouzanOrderLine("YZ-SKU-MISSING", 1)));
    }

    public static YouzanOrderEvent pendingEvent() {
        return new YouzanOrderEvent("YZ-MOCK-WAIT-001", "WAIT_PAY", "MOCK-SHOP-001", "YZ-MOCK-PAID-001",
                "YZ-DEMO-CUSTOMER-1010", null, "WAIT_PAY", 362200L, null,
                Instant.parse("2026-10-23T03:00:00Z"),
                "REGULAR", 1L, 1L, "PLATFORM", LocalDate.parse("2027-12-31"),
                List.of(new YouzanOrderLine("YZ-SKU-301", 2)));
    }
}
