package com.whale.crm.notification.infrastructure.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.notification.domain.WecomSendResult;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Service
public class WecomMessageTokenService {
    private final WecomMessageProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;
    private String token;
    private Instant refreshAt = Instant.EPOCH;

    @Autowired
    public WecomMessageTokenService(WecomMessageProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new OkHttpClient.Builder()
                .connectTimeout(properties.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.readTimeoutMs(), TimeUnit.MILLISECONDS).build());
    }

    public WecomMessageTokenService(WecomMessageProperties properties, ObjectMapper objectMapper, OkHttpClient http) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = http;
    }

    public synchronized String token() {
        if (token == null || !Instant.now().isBefore(refreshAt)) {
            refresh();
        }
        return token;
    }

    public synchronized String refresh() {
        requireCredentials();
        try {
            HttpUrl url = HttpUrl.get(properties.apiBaseUrl() + "/cgi-bin/gettoken").newBuilder()
                    .addQueryParameter("corpid", properties.corpId())
                    .addQueryParameter("corpsecret", properties.secret()).build();
            try (Response response = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
                String raw = response.body() == null ? "" : response.body().string();
                JsonNode body = objectMapper.readTree(raw);
                int code = body.path("errcode").asInt(-1);
                if (!response.isSuccessful() || code != 0 || body.path("access_token").asText().isBlank()) {
                    WecomSendResult.Status status = code == 45009 || response.code() == 429
                            || response.code() >= 500 ? WecomSendResult.Status.RETRYABLE
                            : code == -1 && response.isSuccessful() ? WecomSendResult.Status.RETRYABLE
                            : WecomSendResult.Status.FAILED;
                    throw new WecomMessageClientException(status, "WECOM_TOKEN_ERROR_" + code);
                }
                token = body.path("access_token").asText();
                long expiresIn = Math.max(1L, body.path("expires_in").asLong(7200));
                long skew = Math.max(0L, properties.tokenRefreshSkewSeconds());
                refreshAt = Instant.now().plusSeconds(Math.max(1L, expiresIn - skew));
                return token;
            }
        } catch (WecomMessageClientException ex) {
            throw ex;
        } catch (java.io.IOException | RuntimeException ex) {
            throw new WecomMessageClientException(WecomSendResult.Status.RETRYABLE, "WECOM_TOKEN_UNAVAILABLE");
        }
    }

    private void requireCredentials() {
        if (properties.corpId() == null || properties.corpId().isBlank()
                || properties.secret() == null || properties.secret().isBlank()
                || properties.apiBaseUrl() == null || properties.apiBaseUrl().isBlank()) {
            throw new WecomMessageClientException(WecomSendResult.Status.FAILED, "WECOM_MESSAGE_CONFIG_INVALID");
        }
    }
}
