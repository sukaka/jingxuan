package com.whale.crm.integration.wecom.sidebar.application;

import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRepository;
import com.whale.crm.integration.wecom.domain.WecomStaffIdentity;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarClient;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties;
import com.whale.crm.platform.auth.application.AuthApplicationService;
import com.whale.crm.platform.auth.application.AuthApplicationService.AuthResult;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.HttpUrl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WecomOAuthService {
    private final WecomOfficialProperties officialProperties;
    private final WecomSidebarProperties properties;
    private final WecomSidebarClient client;
    private final WecomOAuthStateService states;
    private final WecomContactSyncRepository repository;
    private final AuthApplicationService authService;

    public WecomOAuthService(WecomOfficialProperties officialProperties, WecomSidebarProperties properties,
                             WecomSidebarClient client, WecomOAuthStateService states,
                             WecomContactSyncRepository repository, AuthApplicationService authService) {
        this.officialProperties = officialProperties;
        this.properties = properties;
        this.client = client;
        this.states = states;
        this.repository = repository;
        this.authService = authService;
    }

    public String authorizationUrl() {
        requireConfigured();
        String state = states.issue(officialProperties.corpId());
        return HttpUrl.get(properties.oauthAuthorizeUrl()).newBuilder()
                .addQueryParameter("appid", officialProperties.corpId())
                .addQueryParameter("redirect_uri", properties.oauthRedirectUri())
                .addQueryParameter("response_type", "code")
                .addQueryParameter("scope", "snsapi_base")
                .addQueryParameter("agentid", Long.toString(properties.agentId()))
                .addQueryParameter("state", state).build().toString() + "#wechat_redirect";
    }

    public AuthResult callback(String code, String state, String clientIp, String userAgent) {
        requireConfigured();
        states.consume(state, officialProperties.corpId());
        String wecomUserid = client.oauthUserid(code);
        List<WecomStaffIdentity> mappings = repository.activeStaffForAuth(officialProperties.corpId(), wecomUserid)
                .stream().distinct().toList();
        if (mappings.size() != 1) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "企微成员未唯一关联 CRM 员工，请联系管理员配置");
        }
        WecomStaffIdentity identity = mappings.getFirst();
        return authService.loginWithVerifiedIdentity(identity.staffUserId(), clientIp, userAgent);
    }

    public String sidebarUrl() {
        return properties.sidebarUrl();
    }

    private void requireConfigured() {
        if (!properties.enabled() || officialProperties.corpId() == null || officialProperties.corpId().isBlank()
                || officialProperties.secret() == null || officialProperties.secret().isBlank()
                || properties.agentId() == null || properties.agentId() <= 0
                || properties.oauthRedirectUri() == null || properties.oauthRedirectUri().isBlank()
                || properties.sidebarUrl() == null || properties.sidebarUrl().isBlank()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "企微 OAuth 尚未配置或未启用");
        }
    }
}
