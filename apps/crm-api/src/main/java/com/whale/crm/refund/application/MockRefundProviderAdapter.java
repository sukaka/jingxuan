package com.whale.crm.refund.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class MockRefundProviderAdapter implements RefundProviderAdapter {
    private static final long WINDOW_SECONDS = 300;
    private final boolean enabled;
    private final String secret;

    public MockRefundProviderAdapter(
            @Value("${app.refunds.mock.enabled:false}") boolean enabled,
            @Value("${app.refunds.mock.secret:}") String secret) {
        this.enabled = enabled;
        this.secret = secret;
    }

    @Override
    public boolean supports(String provider) {
        return "MOCK".equals(provider);
    }

    @Override
    public boolean verify(String rawBody, String signature, Long timestamp) {
        long now = Instant.now().getEpochSecond();
        if (!enabled || secret == null || secret.isBlank() || rawBody == null || rawBody.isBlank()
                || signature == null || signature.isBlank() || timestamp == null
                || timestamp < now - WINDOW_SECONDS || timestamp > now + WINDOW_SECONDS) {
            return false;
        }
        String expected = sign(rawBody, timestamp);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    public String sign(String rawBody, long timestamp) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Mock退款回调密钥未配置");
        }
        if (rawBody == null) {
            throw new IllegalArgumentException("回调报文不能为空");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + rawBody).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Mock退款签名计算失败", ex);
        }
    }
}
