package com.whale.crm.platform.auth.application;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

@Service
public class CaptchaService {
    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final String secret;
    private final long ttlSeconds;
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(StringRedisTemplate redis,
                          @Value("${app.security.auth.captcha.key-prefix:crm:auth:captcha:}") String keyPrefix,
                          @Value("${app.security.auth.jwt-secret}") String secret,
                          @Value("${app.security.auth.captcha.ttl-seconds:300}") long ttlSeconds) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
    }

    public CaptchaChallenge issue() {
        int left = 1 + random.nextInt(9);
        int right = 1 + random.nextInt(9);
        boolean addition = random.nextBoolean();
        if (!addition && left < right) {
            int temporary = left;
            left = right;
            right = temporary;
        }
        String question = left + (addition ? " + " : " − ") + right + " = ?";
        String answer = Integer.toString(addition ? left + right : left - right);
        String id = UUID.randomUUID().toString();
        try {
            redis.opsForValue().set(keyPrefix + id, hash(answer), Duration.ofSeconds(ttlSeconds));
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "登录保护服务暂不可用，请稍后重试");
        }
        return new CaptchaChallenge(id, render(question), answer, ttlSeconds);
    }

    public void verify(String id, String answer) {
        if (id == null || id.isBlank() || id.length() > 64 || answer == null || answer.isBlank() || answer.length() > 8) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请输入正确的验证码");
        }
        String expected;
        try {
            expected = redis.opsForValue().getAndDelete(keyPrefix + id);
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "登录保护服务暂不可用，请稍后重试");
        }
        String actual = hash(answer.strip());
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "验证码错误或已过期，请刷新后重试");
        }
    }

    String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("验证码摘要计算失败", ex);
        }
    }

    private String render(String question) {
        try {
            BufferedImage image = new BufferedImage(160, 52, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(new Color(246, 249, 248));
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                for (int index = 0; index < 5; index++) {
                    graphics.setColor(new Color(105 + random.nextInt(90), 145 + random.nextInt(65), 140 + random.nextInt(65)));
                    graphics.setStroke(new BasicStroke(1.2f));
                    graphics.drawLine(random.nextInt(160), random.nextInt(52), random.nextInt(160), random.nextInt(52));
                }
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
                graphics.setColor(new Color(36, 78, 76));
                graphics.drawString(question, 18 + random.nextInt(8), 35 + random.nextInt(4));
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("验证码图片生成失败", ex);
        }
    }

    public record CaptchaChallenge(String captchaId, String imageBase64, String answer, long expiresIn) {
    }
}
