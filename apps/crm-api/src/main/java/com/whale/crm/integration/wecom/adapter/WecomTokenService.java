package com.whale.crm.integration.wecom.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class WecomTokenService {
    private static final String KEY_PREFIX = "wecom:access_token:";

    private final WecomOfficialProperties properties;
    private final OkHttpClient http;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    @Autowired
    public WecomTokenService(WecomOfficialProperties properties, ObjectMapper objectMapper,
                             StringRedisTemplate redis) {
        this(properties, objectMapper, new OkHttpClient.Builder()
                .connectTimeout(properties.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.readTimeoutMs(), TimeUnit.MILLISECONDS).build(), redis);
    }

    public WecomTokenService(WecomOfficialProperties properties, ObjectMapper objectMapper,
                             OkHttpClient http, StringRedisTemplate redis) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = http;
        this.redis = redis;
    }

    public String token() {
        String key = cacheKey();
        String cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = readCache(key);
            if (cached != null) {
                return cached;
            }
            return refresh();
        }
    }

    public synchronized String refresh() {
        String corpId = requireCorpId();
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "企微官方应用密钥尚未配置");
        }
        HttpUrl url = HttpUrl.get(properties.apiBaseUrl() + "/cgi-bin/gettoken").newBuilder()
                .addQueryParameter("corpid", corpId)
                .addQueryParameter("corpsecret", secret).build();
        try (Response response = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
            JsonNode body = objectMapper.readTree(WecomHttpResponses.body(response, objectMapper));
            int code = body.path("errcode").asInt(-1);
            String accessToken = body.path("access_token").asText("");
            if (!response.isSuccessful() || code != 0 || accessToken.isBlank()) {
                throw new BusinessException(ErrorCode.CONFLICT, "企微访问凭证获取失败，errcode=" + code);
            }
            long ttlSeconds = Math.max(60L, body.path("expires_in").asLong(7200L) - 60L);
            writeCache(KEY_PREFIX + corpId, accessToken, Duration.ofSeconds(ttlSeconds));
            return accessToken;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微访问凭证服务暂时不可用");
        }
    }

    private String cacheKey() {
        return KEY_PREFIX + requireCorpId();
    }

    private String requireCorpId() {
        String corpId = properties.corpId();
        if (corpId == null || corpId.isBlank()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "企微企业 ID 尚未配置");
        }
        return corpId.strip();
    }

    private String readCache(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null || value.isBlank() ? null : value;
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "企微访问凭证缓存暂不可用");
        }
    }

    private void writeCache(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "企微访问凭证缓存暂不可用");
        }
    }
}
