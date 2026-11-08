package com.whale.crm.integration.wecom.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.wecom.domain.WecomContactProfile;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class OfficialWecomContactClient implements WecomContactClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final WecomOfficialProperties properties;
    private final WecomTokenService tokens;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;

    @Autowired
    public OfficialWecomContactClient(WecomOfficialProperties properties, WecomTokenService tokens,
                                      ObjectMapper objectMapper) {
        this(properties, tokens, objectMapper, new OkHttpClient.Builder()
                .connectTimeout(properties.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.readTimeoutMs(), TimeUnit.MILLISECONDS).build());
    }

    public OfficialWecomContactClient(WecomOfficialProperties properties, WecomTokenService tokens,
                                      ObjectMapper objectMapper, OkHttpClient http) {
        this.properties = properties;
        this.tokens = tokens;
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override
    public String name() { return "OFFICIAL_CONTACT"; }

    @Override
    public String corpId() { return properties.corpId(); }

    @Override
    public WecomContactBatchPage batchGetByUser(List<String> wecomUserids, String cursor, int limit) {
        if (wecomUserids.isEmpty() || wecomUserids.size() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "企微批量客户查询成员数必须为1到100");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("userid_list", wecomUserids);
        body.put("limit", Math.max(1, Math.min(limit, 100)));
        if (cursor != null && !cursor.isBlank()) {
            body.put("cursor", cursor);
        }
        JsonNode response = execute("/cgi-bin/externalcontact/batch/get_by_user", body);
        List<WecomContactProfile> contacts = new ArrayList<>();
        for (JsonNode item : response.path("external_contact_list")) {
            JsonNode external = item.path("external_contact");
            JsonNode follow = item.path("follow_info");
            contacts.add(profile(external, follow));
        }
        return new WecomContactBatchPage(List.copyOf(contacts), response.path("next_cursor").asText(""));
    }

    @Override
    public List<WecomContactProfile> getContact(String externalUserid) {
        List<WecomContactProfile> result = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            HttpUrl.Builder builder = HttpUrl.get(properties.apiBaseUrl() + "/cgi-bin/externalcontact/get").newBuilder()
                    .addQueryParameter("access_token", tokens.token())
                    .addQueryParameter("external_userid", externalUserid);
            if (cursor != null && !cursor.isBlank()) {
                builder.addQueryParameter("cursor", cursor);
            }
            JsonNode response = execute(new Request.Builder().url(builder.build()).get().build(), true);
            JsonNode external = response.path("external_contact");
            for (JsonNode follow : response.path("follow_user")) {
                result.add(profile(external, follow));
            }
            cursor = response.path("next_cursor").asText("");
        } while (cursor != null && !cursor.isBlank() && ++pages < properties.maxPages());
        if (cursor != null && !cursor.isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微客户跟进人分页超过安全上限");
        }
        return List.copyOf(result);
    }

    private WecomContactProfile profile(JsonNode external, JsonNode follow) {
        long created = follow.path("createtime").asLong(0);
        return new WecomContactProfile(corpId(), external.path("external_userid").asText(),
                follow.path("userid").asText(), text(external, "name"), text(external, "avatar"),
                nullableInt(external, "type"), nullableInt(external, "gender"), text(external, "unionid"),
                text(follow, "remark"), text(follow, "description"), nullableInt(follow, "add_way"),
                text(follow, "state"), created > 0 ? Instant.ofEpochSecond(created) : null,
                follow.path("tags").isMissingNode() ? follow.path("tag_id").toString() : follow.path("tags").toString());
    }

    private JsonNode execute(String path, Map<String, Object> body) {
        try {
            HttpUrl url = HttpUrl.get(properties.apiBaseUrl() + path).newBuilder()
                    .addQueryParameter("access_token", tokens.token()).build();
            Request request = new Request.Builder().url(url)
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON)).build();
            return execute(request, true);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微客户请求构造失败");
        }
    }

    private JsonNode execute(Request request, boolean retryToken) {
        try (Response response = http.newCall(request).execute()) {
            JsonNode body = objectMapper.readTree(WecomHttpResponses.body(response, objectMapper));
            int code = body.path("errcode").asInt(response.isSuccessful() ? 0 : -1);
            if (retryToken && (code == 40014 || code == 42001)) {
                String fresh = tokens.refresh();
                Request retry = request.newBuilder().url(request.url().newBuilder()
                        .setQueryParameter("access_token", fresh).build()).build();
                return execute(retry, false);
            }
            if (!response.isSuccessful() || code != 0) {
                ErrorCode error = code == 45009 ? ErrorCode.RATE_LIMITED : ErrorCode.CONFLICT;
                throw new BusinessException(error, "企微客户接口调用失败，errcode=" + code);
            }
            return body;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微客户接口暂时不可用");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Integer nullableInt(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asInt() : null;
    }
}
