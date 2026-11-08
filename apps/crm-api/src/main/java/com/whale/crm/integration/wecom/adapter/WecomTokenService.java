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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Service
public class WecomTokenService {
    private final WecomOfficialProperties properties;
    private final OkHttpClient http;
    private final ObjectMapper objectMapper;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    public WecomTokenService(WecomOfficialProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new OkHttpClient.Builder()
                .connectTimeout(properties.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.readTimeoutMs(), TimeUnit.MILLISECONDS).build());
    }

    public WecomTokenService(WecomOfficialProperties properties, ObjectMapper objectMapper, OkHttpClient http) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = http;
    }

    public synchronized String token() {
        if (token == null || Instant.now().plusSeconds(60).isAfter(expiresAt)) {
            refresh();
        }
        return token;
    }

    public synchronized String refresh() {
        HttpUrl url = HttpUrl.get(properties.apiBaseUrl() + "/cgi-bin/gettoken").newBuilder()
                .addQueryParameter("corpid", properties.corpId())
                .addQueryParameter("corpsecret", properties.secret()).build();
        try (Response response = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
            JsonNode body = objectMapper.readTree(WecomHttpResponses.body(response, objectMapper));
            int code = body.path("errcode").asInt(-1);
            if (!response.isSuccessful() || code != 0 || body.path("access_token").asText().isBlank()) {
                throw new BusinessException(ErrorCode.CONFLICT, "企微访问凭证获取失败，errcode=" + code);
            }
            token = body.path("access_token").asText();
            expiresAt = Instant.now().plusSeconds(Math.max(60, body.path("expires_in").asLong(7200) - 60));
            return token;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微访问凭证服务暂时不可用");
        }
    }
}
