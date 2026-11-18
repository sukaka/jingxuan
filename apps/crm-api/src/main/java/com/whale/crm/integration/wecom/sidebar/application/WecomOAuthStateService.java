package com.whale.crm.integration.wecom.sidebar.application;

import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
public class WecomOAuthStateService {
    private final StringRedisTemplate redis;
    private final WecomSidebarProperties properties;
    private final SecureRandom random = new SecureRandom();

    public WecomOAuthStateService(StringRedisTemplate redis, WecomSidebarProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public String issue(String corpId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            redis.opsForValue().set(key(state), corpId,
                    Duration.ofSeconds(Math.max(60, properties.oauthStateTtlSeconds())));
            return state;
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "企微登录状态服务暂不可用");
        }
    }

    public void consume(String state, String corpId) {
        if (state == null || state.isBlank() || state.length() > 128) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "企微登录状态无效或已过期");
        }
        String stored;
        try {
            stored = redis.opsForValue().getAndDelete(key(state));
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "企微登录状态服务暂不可用");
        }
        if (stored == null || !MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                corpId.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "企微登录状态无效或已过期");
        }
    }

    private String key(String state) {
        return "wecom:oauth:state:" + state;
    }
}
