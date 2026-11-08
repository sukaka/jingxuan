package com.whale.crm.platform.auth.application;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

@Service
public class LoginAttemptService {
    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final int maxAccountFailures;
    private final int maxIpFailures;
    private final long lockSeconds;

    public LoginAttemptService(StringRedisTemplate redis,
                               @Value("${app.security.auth.login-attempt.key-prefix:crm:auth:login-attempt:}") String keyPrefix,
                               @Value("${app.security.auth.login-attempt.account-max-failures:5}") int maxAccountFailures,
                               @Value("${app.security.auth.login-attempt.ip-max-failures:30}") int maxIpFailures,
                               @Value("${app.security.auth.login-attempt.lock-seconds:900}") long lockSeconds) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.maxAccountFailures = maxAccountFailures;
        this.maxIpFailures = maxIpFailures;
        this.lockSeconds = lockSeconds;
    }

    public void checkAllowed(String username, String clientIp) {
        if (count(key(username, "account")) >= maxAccountFailures || count(key(clientIp, "ip")) >= maxIpFailures) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "登录尝试次数过多，请15分钟后再试");
        }
    }

    public void recordFailure(String username, String clientIp) {
        long accountAttempts = increment(key(username, "account"));
        long ipAttempts = increment(key(clientIp, "ip"));
        if (accountAttempts >= maxAccountFailures || ipAttempts >= maxIpFailures) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "登录尝试次数过多，请15分钟后再试");
        }
    }

    public void recordSuccess(String username) {
        try {
            redis.delete(key(username, "account"));
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "登录保护服务暂不可用，请稍后重试");
        }
    }

    private long count(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (DataAccessException | NumberFormatException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "登录保护服务暂不可用，请稍后重试");
        }
    }

    public void checkCaptchaAllowed(String clientIp) {
        if (increment(key(clientIp, "captcha-issue"), 60) > 30) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "验证码获取过于频繁，请稍后再试");
        }
    }

    private long increment(String key) {
        return increment(key, lockSeconds);
    }

    private long increment(String key, long expirySeconds) {
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redis.expire(key, Duration.ofSeconds(expirySeconds));
            }
            return value == null ? 0 : value;
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "登录保护服务暂不可用，请稍后重试");
        }
    }

    private String key(String value, String type) {
        return keyPrefix + type + ":" + sha256(value == null || value.isBlank() ? "unknown" : value.strip().toLowerCase(java.util.Locale.ROOT));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("登录失败计数摘要失败", ex);
        }
    }
}
