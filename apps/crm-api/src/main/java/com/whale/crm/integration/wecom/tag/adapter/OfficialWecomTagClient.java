package com.whale.crm.integration.wecom.tag.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.adapter.WecomTokenService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 企业微信官方企业标签客户端：仅调用企微官方 /cgi-bin/externalcontact/* 标签接口。
 * 凭证由 WecomTokenService 统一管理，遇到 40014/42001 自动刷新重试一次；错误一律脱敏。
 */
@Component
public class OfficialWecomTagClient implements WecomTagClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int ERRCODE_INVALID_TOKEN = 40014;
    private static final int ERRCODE_EXPIRED_TOKEN = 42001;
    private static final int ERRCODE_RATE_LIMITED = 45009;

    private final WecomOfficialProperties properties;
    private final WecomTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;

    @Autowired
    public OfficialWecomTagClient(WecomOfficialProperties properties, WecomTokenService tokenService,
                                  ObjectMapper objectMapper) {
        this(properties, tokenService, objectMapper, new OkHttpClient.Builder()
                .connectTimeout(properties.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.readTimeoutMs(), TimeUnit.MILLISECONDS).build());
    }

    public OfficialWecomTagClient(WecomOfficialProperties properties, WecomTokenService tokenService,
                                  ObjectMapper objectMapper, OkHttpClient http) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override
    public String name() {
        return "OFFICIAL_WECOM_TAG";
    }

    @Override
    public String corpId() {
        return properties.corpId();
    }

    @Override
    public List<WecomCorpTag> listCorpTags() {
        JsonNode response = postJson("/cgi-bin/externalcontact/get_corp_tag_list", Map.of());
        List<WecomCorpTag> result = new ArrayList<>();
        for (JsonNode group : response.path("tag_group")) {
            if (group.path("deleted").asBoolean(false)) {
                continue;
            }
            String groupId = text(group, "group_id");
            String groupName = text(group, "group_name");
            for (JsonNode tag : group.path("tag")) {
                if (tag.path("deleted").asBoolean(false)) {
                    continue;
                }
                String tagId = text(tag, "id");
                String tagName = text(tag, "name");
                if (tagId != null && !tagId.isBlank() && tagName != null && !tagName.isBlank()) {
                    result.add(new WecomCorpTag(groupId, groupName, tagId, tagName, tag.path("order").asInt(0)));
                }
            }
        }
        return List.copyOf(result);
    }

    @Override
    public WecomCorpTag addCorpTag(String groupId, String groupName, String tagName, int order) {
        if (tagName == null || tagName.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "企微标签名称不能为空");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (groupId != null && !groupId.isBlank()) {
            body.put("group_id", groupId.strip());
        } else {
            String resolvedGroup = groupName != null && !groupName.isBlank()
                    ? groupName.strip() : properties.defaultTagGroupName();
            body.put("group_name", resolvedGroup);
        }
        Map<String, Object> tagPayload = new LinkedHashMap<>();
        tagPayload.put("name", tagName.strip());
        tagPayload.put("order", Math.max(0, order));
        body.put("tag", List.of(tagPayload));

        JsonNode response = postJson("/cgi-bin/externalcontact/add_corp_tag", body);
        JsonNode group = response.path("tag_group");
        JsonNode tags = group.path("tag");
        if (!tags.isArray() || tags.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微创建标签响应缺少标签明细");
        }
        JsonNode created = tags.get(0);
        String createdId = text(created, "id");
        if (createdId == null || createdId.isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微创建标签响应缺少标签ID");
        }
        return new WecomCorpTag(
                text(group, "group_id"),
                text(group, "group_name"),
                createdId,
                text(created, "name") != null ? text(created, "name") : tagName.strip(),
                created.path("order").asInt(Math.max(0, order)));
    }

    @Override
    public void editCorpTag(String tagId, String tagName, int order) {
        if (tagId == null || tagId.isBlank() || tagName == null || tagName.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "企微标签ID和名称不能为空");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", tagId.strip());
        body.put("name", tagName.strip());
        body.put("order", Math.max(0, order));
        postJson("/cgi-bin/externalcontact/edit_corp_tag", body);
    }

    @Override
    public void deleteCorpTag(String tagId) {
        if (tagId == null || tagId.isBlank()) {
            return;
        }
        Map<String, Object> body = Map.of("tag_id", List.of(tagId.strip()));
        postJson("/cgi-bin/externalcontact/del_corp_tag", body);
    }

    @Override
    public void markCustomerTags(String wecomUserid, String externalUserid, List<String> addTagIds,
                                 List<String> removeTagIds) {
        List<String> adds = addTagIds == null ? List.of()
                : addTagIds.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).distinct().toList();
        List<String> removes = removeTagIds == null ? List.of()
                : removeTagIds.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).distinct().toList();
        if (adds.isEmpty() && removes.isEmpty()) {
            return;
        }
        if (wecomUserid == null || wecomUserid.isBlank() || externalUserid == null || externalUserid.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "企微打标缺少成员或外部客户标识");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userid", wecomUserid.strip());
        body.put("external_userid", externalUserid.strip());
        if (!adds.isEmpty()) {
            body.put("add_tag", adds);
        }
        if (!removes.isEmpty()) {
            body.put("remove_tag", removes);
        }
        postJson("/cgi-bin/externalcontact/mark_tag", body);
    }

    private JsonNode postJson(String path, Map<String, Object> body) {
        try {
            HttpUrl base = HttpUrl.parse(properties.apiBaseUrl() + path);
            if (base == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "企微接口地址配置无效");
            }
            HttpUrl url = base.newBuilder()
                    .addQueryParameter("access_token", tokenService.token())
                    .build();
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                    .build();
            return execute(request, true);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微标签请求构造失败");
        }
    }

    private JsonNode execute(Request request, boolean retryToken) {
        try (Response response = http.newCall(request).execute()) {
            JsonNode body = readBoundedJson(response);
            int code = body.path("errcode").asInt(response.isSuccessful() ? 0 : -1);
            if (retryToken && (code == ERRCODE_INVALID_TOKEN || code == ERRCODE_EXPIRED_TOKEN)) {
                String fresh = tokenService.refresh();
                Request retry = request.newBuilder()
                        .url(request.url().newBuilder().setQueryParameter("access_token", fresh).build())
                        .build();
                return execute(retry, false);
            }
            if (!response.isSuccessful() || code != 0) {
                ErrorCode error = code == ERRCODE_RATE_LIMITED ? ErrorCode.RATE_LIMITED : ErrorCode.CONFLICT;
                throw new BusinessException(error, "企微标签接口调用失败（错误码：" + code + "）");
            }
            return body;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微标签接口暂时不可用");
        }
    }

    private JsonNode readBoundedJson(Response response) {
        try {
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
                throw new BusinessException(ErrorCode.CONFLICT, "企微标签接口响应无效或过大");
            }
            String value = body.string();
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                throw new BusinessException(ErrorCode.CONFLICT, "企微标签接口响应超过大小限制");
            }
            return objectMapper.readTree(value);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微标签接口返回了无法解析的响应");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
