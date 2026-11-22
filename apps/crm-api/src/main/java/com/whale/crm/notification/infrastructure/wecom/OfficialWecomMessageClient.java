package com.whale.crm.notification.infrastructure.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whale.crm.notification.domain.WecomMessageClient;
import com.whale.crm.notification.domain.WecomMessageCommand;
import com.whale.crm.notification.domain.WecomSendResult;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class OfficialWecomMessageClient implements WecomMessageClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_BODY_CHARS = 65536;
    private static final int MAX_TEXT_BYTES = 2048;

    private final WecomMessageProperties properties;
    private final WecomMessageTokenService tokens;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;

    @Autowired
    public OfficialWecomMessageClient(WecomMessageProperties properties, WecomMessageTokenService tokens,
                                      ObjectMapper objectMapper) {
        this(properties, tokens, objectMapper, new OkHttpClient.Builder()
                .connectTimeout(properties.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.readTimeoutMs(), TimeUnit.MILLISECONDS)
                .followRedirects(false).build());
    }

    public OfficialWecomMessageClient(WecomMessageProperties properties, WecomMessageTokenService tokens,
                                      ObjectMapper objectMapper, OkHttpClient http) {
        this.properties = properties;
        this.tokens = tokens;
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override
    public WecomSendResult send(WecomMessageCommand command) {
        if (command == null || command.wecomUserId() == null || command.wecomUserId().isBlank()
                || properties.agentId() == null || properties.agentId() <= 0) {
            return result(WecomSendResult.Status.FAILED, "WECOM_MESSAGE_CONFIG_INVALID");
        }
        if (properties.allowedTenantId() <= 0 || command.tenantId() != properties.allowedTenantId()) {
            return result(WecomSendResult.Status.FAILED, "WECOM_MESSAGE_TENANT_FORBIDDEN");
        }
        final Request request;
        try {
            ObjectNode text = objectMapper.createObjectNode();
            text.put("content", truncateUtf8(command.title() + "\n" + command.contentMasked(), MAX_TEXT_BYTES));
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("touser", command.wecomUserId());
            payload.put("msgtype", "text");
            payload.put("agentid", properties.agentId());
            payload.set("text", text);
            HttpUrl url = HttpUrl.get(properties.apiBaseUrl() + "/cgi-bin/message/send").newBuilder()
                    .addQueryParameter("access_token", tokens.token()).build();
            request = new Request.Builder().url(url)
                    .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON)).build();
        } catch (WecomMessageClientException ex) {
            return result(ex.status(), ex.getMessage());
        } catch (Exception ex) {
            return result(WecomSendResult.Status.FAILED, "WECOM_MESSAGE_CONFIG_INVALID");
        }
        return execute(request, true);
    }

    private WecomSendResult execute(Request request, boolean retryToken) {
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (raw.length() > MAX_BODY_CHARS) {
                return result(WecomSendResult.Status.UNKNOWN, "WECOM_RESPONSE_INVALID");
            }
            JsonNode body = objectMapper.readTree(raw);
            int code = body.path("errcode").asInt(-1);
            if (retryToken && (code == 40014 || code == 42001)) {
                String fresh = tokens.refresh();
                Request retry = request.newBuilder().url(request.url().newBuilder()
                        .setQueryParameter("access_token", fresh).build()).build();
                return execute(retry, false);
            }
            if (code == 45009 || response.code() == 429 || response.code() >= 500) {
                return result(WecomSendResult.Status.RETRYABLE, "WECOM_RATE_LIMIT_" + code);
            }
            if (code == 40014 || code == 42001) {
                return result(WecomSendResult.Status.RETRYABLE, "WECOM_TOKEN_REJECTED_" + code);
            }
            if (!response.isSuccessful()) {
                return result(WecomSendResult.Status.FAILED, "WECOM_HTTP_" + response.code());
            }
            if (code != 0) {
                return result(WecomSendResult.Status.FAILED, "WECOM_ERROR_" + code);
            }
            if (!body.path("invaliduser").asText("").isBlank()) {
                return result(WecomSendResult.Status.FAILED, "WECOM_INVALID_RECIPIENT");
            }
            String messageId = body.path("msgid").isMissingNode() || body.path("msgid").isNull()
                    ? null : body.path("msgid").asText();
            return new WecomSendResult(WecomSendResult.Status.SENT, "OFFICIAL", messageId, null);
        } catch (WecomMessageClientException ex) {
            return result(ex.status(), ex.getMessage());
        } catch (Exception ex) {
            return result(WecomSendResult.Status.UNKNOWN, "WECOM_RESPONSE_UNKNOWN");
        }
    }

    private WecomSendResult result(WecomSendResult.Status status, String error) {
        return new WecomSendResult(status, "OFFICIAL", null, error);
    }

    private String truncateUtf8(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) {
                break;
            }
            result.append(character);
            bytes += size;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }
}
