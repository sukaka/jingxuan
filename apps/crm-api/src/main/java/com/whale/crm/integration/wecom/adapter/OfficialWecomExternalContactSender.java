package com.whale.crm.integration.wecom.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRepository;
import com.whale.crm.integration.wecom.domain.WecomStaffAccount;
import com.whale.crm.notification.domain.WecomSendResult;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class OfficialWecomExternalContactSender implements ExternalContactMessageSender {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_BODY_CHARS = 65536;
    private static final int MAX_TEXT_BYTES = 2048;

    private final boolean officialEnabled;
    private final WecomOfficialProperties properties;
    private final WecomTokenService tokenService;
    private final WecomContactSyncRepository contacts;
    private final com.whale.crm.integration.config.application.IntegrationRuntimeConfigService runtimeConfigService;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;

    @Autowired
    public OfficialWecomExternalContactSender(
            @Value("${app.wecom.external-contact.official-enabled:false}") boolean officialEnabled,
            WecomOfficialProperties properties, WecomTokenService tokenService, WecomContactSyncRepository contacts,
            com.whale.crm.integration.config.application.IntegrationRuntimeConfigService runtimeConfigService,
            ObjectMapper objectMapper) {
        this(officialEnabled, properties, tokenService, contacts, runtimeConfigService, objectMapper,
                new OkHttpClient.Builder().connectTimeout(properties.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                        .readTimeout(properties.readTimeoutMs(), TimeUnit.MILLISECONDS)
                        .followRedirects(false).build());
    }

    OfficialWecomExternalContactSender(boolean officialEnabled, WecomOfficialProperties properties,
                                       WecomTokenService tokenService, WecomContactSyncRepository contacts,
                                       com.whale.crm.integration.config.application.IntegrationRuntimeConfigService runtimeConfigService,
                                       ObjectMapper objectMapper, OkHttpClient http) {
        this.officialEnabled = officialEnabled;
        this.properties = properties;
        this.tokenService = tokenService;
        this.contacts = contacts;
        this.runtimeConfigService = runtimeConfigService;
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override
    public WecomSendResult send(ExternalContactMessageCommand command) {
        if (!officialEnabled || !properties.enabled()) {
            return result(WecomSendResult.Status.FAILED, "WECOM_EXTERNAL_CONTACT_OFFICIAL_DISABLED");
        }
        if (command == null || command.externalUserid() == null || command.externalUserid().isBlank()
                || command.text() == null || command.text().isBlank()
                || command.text().getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            return result(WecomSendResult.Status.FAILED, "WECOM_EXTERNAL_CONTACT_TEXT_INVALID");
        }
        if (properties.allowedTenantId() <= 0 || command.tenantId() != properties.allowedTenantId()
                || properties.corpId() == null || !properties.corpId().equals(command.corpId())) {
            return result(WecomSendResult.Status.FAILED, "WECOM_EXTERNAL_CONTACT_TENANT_FORBIDDEN");
        }
        if (!runtimeConfigService.wecomOfficialEnabled(command.tenantId())) {
            return result(WecomSendResult.Status.FAILED, "WECOM_EXTERNAL_CONTACT_OFFICIAL_DISABLED");
        }
        var mappings = contacts.staffByUserId(command.tenantId(), command.corpId(), command.staffUserId())
                .stream().filter(WecomStaffAccount::enabled).toList();
        if (mappings.size() != 1 || mappings.getFirst().wecomUserid() == null
                || mappings.getFirst().wecomUserid().isBlank()) {
            return result(WecomSendResult.Status.FAILED, "WECOM_EXTERNAL_CONTACT_STAFF_UNMAPPED");
        }
        return execute(command, mappings.getFirst().wecomUserid(), true);
    }

    private WecomSendResult execute(ExternalContactMessageCommand command, String wecomUserId, boolean refreshToken) {
        final Request request;
        try {
            ObjectNode text = objectMapper.createObjectNode();
            text.put("content", command.text());
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("touser", command.externalUserid());
            payload.put("sender", wecomUserId);
            payload.put("msgtype", "text");
            payload.put("client_msgid", command.idempotencyKey());
            payload.set("text", text);
            HttpUrl url = HttpUrl.get(properties.apiBaseUrl() + "/cgi-bin/externalcontact/message/send")
                    .newBuilder().addQueryParameter("access_token", tokenService.token()).build();
            request = new Request.Builder().url(url)
                    .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON)).build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
            return result(WecomSendResult.Status.FAILED, "WECOM_EXTERNAL_CONTACT_CONFIG_INVALID");
        } catch (com.whale.crm.platform.web.BusinessException ex) {
            return result(WecomSendResult.Status.RETRYABLE, "WECOM_EXTERNAL_CONTACT_TOKEN_UNAVAILABLE");
        }
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (raw.length() > MAX_BODY_CHARS) {
                return result(WecomSendResult.Status.UNKNOWN, "WECOM_EXTERNAL_CONTACT_RESPONSE_UNKNOWN");
            }
            JsonNode body = objectMapper.readTree(raw);
            int code = body.path("errcode").asInt(-1);
            if (refreshToken && (code == 40014 || code == 42001)) {
                tokenService.refresh();
                return execute(command, wecomUserId, false);
            }
            if (code == 45009 || response.code() == 429) {
                return result(WecomSendResult.Status.RETRYABLE, "WECOM_EXTERNAL_CONTACT_RATE_LIMIT");
            }
            if (!response.isSuccessful() || code == -1) {
                return result(WecomSendResult.Status.UNKNOWN, "WECOM_EXTERNAL_CONTACT_RESPONSE_UNKNOWN");
            }
            if (code != 0) {
                return result(WecomSendResult.Status.FAILED, "WECOM_EXTERNAL_CONTACT_ERROR_" + code);
            }
            String messageId = body.hasNonNull("msgid") ? body.path("msgid").asText()
                    : body.hasNonNull("msg_id") ? body.path("msg_id").asText() : null;
            return new WecomSendResult(WecomSendResult.Status.SENT, "OFFICIAL", messageId, null);
        } catch (com.whale.crm.platform.web.BusinessException ex) {
            return result(WecomSendResult.Status.RETRYABLE, "WECOM_EXTERNAL_CONTACT_TOKEN_UNAVAILABLE");
        } catch (java.io.IOException | RuntimeException ex) {
            return result(WecomSendResult.Status.UNKNOWN, "WECOM_EXTERNAL_CONTACT_RESPONSE_UNKNOWN");
        }
    }

    private WecomSendResult result(WecomSendResult.Status status, String error) {
        return new WecomSendResult(status, "OFFICIAL", null, error);
    }

    @Override
    public String mode() {
        return "OFFICIAL";
    }
}
