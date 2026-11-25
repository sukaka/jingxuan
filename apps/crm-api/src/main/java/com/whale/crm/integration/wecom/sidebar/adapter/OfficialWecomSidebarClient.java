package com.whale.crm.integration.wecom.sidebar.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.adapter.WecomTokenService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class OfficialWecomSidebarClient implements WecomSidebarClient {
    private final WecomOfficialProperties properties;
    private final WecomTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;

    public OfficialWecomSidebarClient(WecomOfficialProperties properties, WecomTokenService tokenService,
                                      ObjectMapper objectMapper) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(properties.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.readTimeoutMs(), TimeUnit.MILLISECONDS).build();
    }

    @Override
    public String oauthUserid(String code) {
        HttpUrl url = HttpUrl.get(properties.apiBaseUrl() + "/cgi-bin/auth/getuserinfo").newBuilder()
                .addQueryParameter("access_token", tokenService.token())
                .addQueryParameter("code", code).build();
        JsonNode body = execute(new Request.Builder().url(url).get().build(), true, true);
        String userid = body.path("userid").asText("");
        if (userid.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "当前企微用户不是可登录的企业成员");
        }
        return userid;
    }

    @Override
    public WecomTicket ticket(boolean agentConfig) {
        String path = agentConfig ? "/cgi-bin/ticket/get" : "/cgi-bin/get_jsapi_ticket";
        HttpUrl.Builder builder = HttpUrl.get(properties.apiBaseUrl() + path).newBuilder()
                .addQueryParameter("access_token", tokenService.token());
        if (agentConfig) {
            builder.addQueryParameter("type", "agent_config");
        }
        JsonNode body = execute(new Request.Builder().url(builder.build()).get().build(), true, false);
        String ticket = body.path("ticket").asText("");
        long expiresIn = body.path("expires_in").asLong(0);
        if (ticket.isBlank() || ticket.length() > 512 || expiresIn <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微 JS 票据响应无效");
        }
        return new WecomTicket(ticket, expiresIn);
    }

    @Override
    public WecomUploadedMedia uploadTempMedia(String mediaType, String fileName, String contentType, byte[] content) {
        if (!"image".equals(mediaType) && !"file".equals(mediaType)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的企微临时素材类型");
        }
        if (content == null || content.length < 5) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "企微临时素材内容为空或小于最小限制");
        }
        String safeName = sanitizeFileName(fileName, mediaType);
        MediaType partType = parseMediaType(contentType);
        MultipartBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("media", safeName, RequestBody.create(content, partType))
                .build();
        HttpUrl url = HttpUrl.get(properties.apiBaseUrl() + "/cgi-bin/media/upload").newBuilder()
                .addQueryParameter("access_token", tokenService.token())
                .addQueryParameter("type", mediaType).build();
        JsonNode body = execute(new Request.Builder().url(url).post(multipart).build(), true, false);
        String mediaId = body.path("media_id").asText("");
        if (mediaId.isBlank() || mediaId.length() > 256) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微临时素材上传响应无效");
        }
        String returnedType = body.path("type").asText("");
        return new WecomUploadedMedia(mediaId, returnedType.isBlank() ? mediaType : returnedType,
                parseCreatedAt(body.path("created_at")));
    }

    private String sanitizeFileName(String fileName, String mediaType) {
        if (fileName != null) {
            StringBuilder cleaned = new StringBuilder();
            for (int i = 0; i < fileName.length(); i++) {
                char c = fileName.charAt(i);
                if (!Character.isISOControl(c) && c != '"' && c != '/' && c != '\\') {
                    cleaned.append(c);
                }
            }
            String result = cleaned.toString().strip();
            if (!result.isEmpty()) {
                return result.length() > 128 ? result.substring(0, 128) : result;
            }
        }
        return "image".equals(mediaType) ? "material.png" : "material.pdf";
    }

    private MediaType parseMediaType(String contentType) {
        MediaType parsed = contentType == null || contentType.isBlank() ? null : MediaType.parse(contentType);
        return parsed == null ? MediaType.parse("application/octet-stream") : parsed;
    }

    private Instant parseCreatedAt(JsonNode node) {
        try {
            if (node != null && node.isIntegralNumber()) {
                long epoch = node.asLong(0L);
                if (epoch > 0) {
                    return Instant.ofEpochSecond(epoch);
                }
            } else if (node != null && node.isTextual()) {
                long epoch = Long.parseLong(node.asText().strip());
                if (epoch > 0) {
                    return Instant.ofEpochSecond(epoch);
                }
            }
        } catch (Exception ignored) {
            // Fall back to current timestamp if provider returns non-numeric created_at
        }
        return Instant.now();
    }

    private JsonNode execute(Request request, boolean retryToken, boolean oauth) {
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (raw.length() > 65536) {
                throw new BusinessException(ErrorCode.CONFLICT, "企微接口响应超过大小限制");
            }
            JsonNode body = objectMapper.readTree(raw);
            int code = body.path("errcode").asInt(response.isSuccessful() ? 0 : -1);
            if (retryToken && (code == 40014 || code == 42001)) {
                String fresh = tokenService.refresh();
                Request retry = request.newBuilder().url(request.url().newBuilder()
                        .setQueryParameter("access_token", fresh).build()).build();
                return execute(retry, false, oauth);
            }
            if (!response.isSuccessful() || code != 0) {
                ErrorCode error = oauth ? ErrorCode.UNAUTHORIZED : ErrorCode.CONFLICT;
                throw new BusinessException(error, "企微身份或票据服务调用失败（错误码：" + code + "）");
            }
            return body;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            ErrorCode error = oauth ? ErrorCode.UNAUTHORIZED : ErrorCode.CONFLICT;
            throw new BusinessException(error, "企微身份或票据服务暂时不可用");
        }
    }
}
