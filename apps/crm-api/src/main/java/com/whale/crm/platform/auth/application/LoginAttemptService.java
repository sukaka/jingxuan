package com.whale.crm.platform.auth.application;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class LoginAttemptService {

    private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 or redis.call('TTL', KEYS[1]) == -1 then "
                    + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) "
                    + "end "
                    + "return current",
            Long.class);

    private static final String DEFAULT_TENANT_SCOPE = "tenant:" + TenantContext.DEFAULT_TENANT_ID;

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

    public void checkIpAllowed(String clientIp) {
        if (count(key(clientIp, "ip")) >= maxIpFailures) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "登录尝试次数过多，请15分钟后再试");
        }
    }

    public void checkAllowed(String username, String clientIp) {
        checkAllowed(DEFAULT_TENANT_SCOPE, username, clientIp);
    }

    public void checkAllowed(String tenantScope, String username, String clientIp) {
        if (count(accountKey(tenantScope, username)) >= maxAccountFailures
                || count(key(clientIp, "ip")) >= maxIpFailures) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "登录尝试次数过多，请15分钟后再试");
        }
    }

    public void recordFailure(String username, String clientIp) {
        recordFailure(DEFAULT_TENANT_SCOPE, username, clientIp);
    }

    public void recordFailure(String tenantScope, String username, String clientIp) {
        long accountAttempts = increment(accountKey(tenantScope, username));
        long ipAttempts = increment(key(clientIp, "ip"));
        if (accountAttempts >= maxAccountFailures || ipAttempts >= maxIpFailures) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "登录尝试次数过多，请15分钟后再试");
        }
    }

    public void recordCaptchaFailure(String clientIp) {
        long ipAttempts = increment(key(clientIp, "ip"));
        if (ipAttempts >= maxIpFailures) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "登录尝试次数过多，请15分钟后再试");
        }
    }

    public void recordSuccess(String username) {
        recordSuccess(DEFAULT_TENANT_SCOPE, username);
    }

    public void recordSuccess(String tenantScope, String username) {
        try {
            redis.delete(accountKey(tenantScope, username));
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "登录保护服务暂不可用，请稍后重试");
        }
    }

    public void checkCaptchaAllowed(String clientIp) {
        if (increment(key(clientIp, "captcha-issue"), 60) > 30) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "验证码获取过于频繁，请稍后再试");
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

    private long increment(String key) {
        return increment(key, lockSeconds);
    }

    private long increment(String key, long expirySeconds) {
        try {
            Long value = redis.execute(INCREMENT_WITH_EXPIRE_SCRIPT, List.of(key), Long.toString(expirySeconds));
            return value == null ? 0 : value;
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "登录保护服务暂不可用，请稍后重试");
        }
    }

    private String accountKey(String tenantScope, String username) {
        String normalizedScope = tenantScope == null || tenantScope.isBlank()
                ? DEFAULT_TENANT_SCOPE
                : tenantScope.strip().toLowerCase(Locale.ROOT);
        String normalizedUser = username == null || username.isBlank()
                ? "unknown"
                : username.strip().toLowerCase(Locale.ROOT);
        return keyPrefix + "account:" + sha256(normalizedScope + ":" + normalizedUser);
    }

    private String key(String value, String type) {
        return keyPrefix + type + ":" + sha256(value == null || value.isBlank() ? "unknown" : value.strip().toLowerCase(Locale.ROOT));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("登录失败计数摘要失败", ex);
        }
    }
}
