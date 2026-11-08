package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Component
public class YouzanRemoteClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public YouzanRemoteClient(ObjectMapper objectMapper,
                              @Value("${YOUZAN_API_BASE_URL:https://open.youzanyun.com}") String baseUrl) {
        this(new OkHttpClient(), objectMapper, baseUrl);
    }

    YouzanRemoteClient(OkHttpClient client, ObjectMapper objectMapper, String baseUrl) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public YouzanTokenResponse fetchSelfToken(String clientId, String clientSecret, String shopId) {
        Map<String, Object> body = Map.of("client_id", clientId, "client_secret", clientSecret,
                "authorize_type", "silent", "grant_id", shopId, "refresh", true);
        JsonNode response = post(baseUrl + "/auth/token", body);
        JsonNode data = requiredSuccess(response).path("data");
        String accessToken = requiredText(data, "access_token");
        String refreshToken = text(data, "refresh_token");
        long expires = data.path("expires").asLong(0);
        if (expires <= 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "有赞Token响应缺少有效期");
        }
        return new YouzanTokenResponse(accessToken, refreshToken, Instant.ofEpochMilli(expires));
    }

    public JsonNode callApi(String accessToken, String apiName, String version, Map<String, Object> body) {
        String url = baseUrl + "/api/" + apiName + "/" + version + "?access_token="
                + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
        return requiredSuccess(post(url, body));
    }

    private JsonNode post(String url, Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder().url(url).post(RequestBody.create(json, JSON)).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 429) {
                    throw new YouzanApiException(ErrorCode.RATE_LIMITED, "有赞接口限流，请稍后重试", 429,
                            false, true, retryAfter(response.header("Retry-After")));
                }
                if (response.code() == 401 || response.code() == 403) {
                    throw new YouzanApiException(ErrorCode.UNAUTHORIZED, "有赞授权或Token已失效", response.code(),
                            true, false, null);
                }
                if (!response.isSuccessful()) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "有赞接口HTTP调用失败: " + response.code());
                }
                String content = response.body().string();
                return objectMapper.readTree(content);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "有赞接口暂时不可用或响应无效");
        }
    }

    private JsonNode requiredSuccess(JsonNode response) {
        if (response == null || !response.path("success").asBoolean(false) || response.path("code").asInt() != 200) {
            int code = response == null ? 0 : response.path("code").asInt();
            String message = response == null ? "" : response.path("message").asText("");
            if (code == 4101) {
                throw new YouzanApiException(ErrorCode.RATE_LIMITED, "有赞接口限流，请稍后重试", code,
                        false, true, null);
            }
            if (isTokenFailure(message)) {
                throw new YouzanApiException(ErrorCode.UNAUTHORIZED, "有赞授权或Token已失效", code,
                        true, false, null);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "有赞接口返回业务失败: " + code);
        }
        return response;
    }

    private static java.time.Duration retryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isTokenFailure(String message) {
        String value = message == null ? "" : message.toLowerCase();
        boolean credential = value.contains("token") || value.contains("授权");
        boolean invalid = value.contains("过期") || value.contains("失效") || value.contains("无效")
                || value.contains("expired") || value.contains("invalid");
        return credential && invalid;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "有赞响应缺少必填字段");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}
