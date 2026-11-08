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
        long age = Math.abs(Instant.now().getEpochSecond() - signature.timestamp());
        if (age > WINDOW_SECONDS) {
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
