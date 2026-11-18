package com.whale.crm.integration.wecom.sidebar.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.adapter.WecomTokenService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class OfficialWecomSidebarClient implements WecomSidebarClient {
    private final WecomOfficialProperties properties;
    private final WecomTokenService tokens;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;

    public OfficialWecomSidebarClient(WecomOfficialProperties properties, WecomTokenService tokens,
                                      ObjectMapper objectMapper) {
        this.properties = properties;
        this.tokens = tokens;
        this.objectMapper = objectMapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(properties.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.readTimeoutMs(), TimeUnit.MILLISECONDS).build();
    }

    @Override
    public String oauthUserid(String code) {
        HttpUrl url = HttpUrl.get(properties.apiBaseUrl() + "/cgi-bin/auth/getuserinfo").newBuilder()
                .addQueryParameter("access_token", tokens.token())
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
                .addQueryParameter("access_token", tokens.token());
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

    private JsonNode execute(Request request, boolean retryToken, boolean oauth) {
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (raw.length() > 65536) {
                throw new BusinessException(ErrorCode.CONFLICT, "企微接口响应超过大小限制");
            }
            JsonNode body = objectMapper.readTree(raw);
            int code = body.path("errcode").asInt(response.isSuccessful() ? 0 : -1);
            if (retryToken && (code == 40014 || code == 42001)) {
                String fresh = tokens.refresh();
                Request retry = request.newBuilder().url(request.url().newBuilder()
                        .setQueryParameter("access_token", fresh).build()).build();
                return execute(retry, false, oauth);
            }
            if (!response.isSuccessful() || code != 0) {
                ErrorCode error = oauth ? ErrorCode.UNAUTHORIZED : ErrorCode.CONFLICT;
                throw new BusinessException(error, "企微身份或票据服务调用失败，errcode=" + code);
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
