package com.whale.crm.integration.wecom.sidebar.api;

import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.api.WecomBaseController;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomJsSdkSignatureRequest;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomJsSdkSignatureResponse;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarContextResponse;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarGroupContextResponse;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarLinkContactRequest;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarLinkContactResponse;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarMaterialMediaResponse;
import com.whale.crm.integration.wecom.sidebar.application.WecomJsSdkService;
import com.whale.crm.integration.wecom.sidebar.application.WecomOAuthService;
import com.whale.crm.integration.wecom.sidebar.application.WecomSidebarContextService;
import com.whale.crm.integration.wecom.sidebar.application.WecomSidebarMaterialMediaService;
import com.whale.crm.platform.auth.application.AuthApplicationService.AuthResult;
import com.whale.crm.platform.security.AuthSecurityProperties;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import okhttp3.HttpUrl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;

@Validated
@RestController
@RequestMapping("/api/v1/wecom")
public class WecomSidebarController extends WecomBaseController {
    private static final String DEFAULT_OAUTH_ERROR_MESSAGE = "企微登录失败，请重新打开侧边栏或联系管理员";

    private final WecomOAuthService oauthService;
    private final WecomJsSdkService jsSdkService;
    private final WecomSidebarContextService contextService;
    private final WecomSidebarMaterialMediaService materialMediaService;
    private final WecomOfficialProperties officialProperties;
    private final com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties sidebarProperties;
    private final AuthSecurityProperties authProperties;

    public WecomSidebarController(WecomOAuthService oauthService, WecomJsSdkService jsSdkService,
                                  WecomSidebarContextService contextService,
                                  WecomSidebarMaterialMediaService materialMediaService,
                                  WecomOfficialProperties officialProperties,
                                  com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties sidebarProperties,
                                  AuthSecurityProperties authProperties) {
        this.oauthService = oauthService;
        this.jsSdkService = jsSdkService;
        this.contextService = contextService;
        this.materialMediaService = materialMediaService;
        this.officialProperties = officialProperties;
        this.sidebarProperties = sidebarProperties;
        this.authProperties = authProperties;
    }

    @GetMapping("/oauth/authorize")
    public ResponseEntity<Void> authorize() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthService.authorizationUrl())).build();
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         HttpServletRequest request) {
        if (code == null || code.isBlank() || code.length() > 512
                || state == null || state.isBlank() || state.length() > 128) {
            return oauthErrorRedirect(ErrorCode.VALIDATION_FAILED, "企微登录回调参数缺失或无效，请重新从企微侧边栏打开");
        }
        try {
            AuthResult result = oauthService.callback(code, state, request.getRemoteAddr(),
                    request.getHeader(HttpHeaders.USER_AGENT));
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(oauthService.sidebarUrl()))
                    .header(HttpHeaders.SET_COOKIE, cookie(authProperties.tokenCookieName(), result.token(), true).toString())
                    .header(HttpHeaders.SET_COOKIE, cookie(authProperties.csrfCookieName(), result.csrfToken(), false).toString())
                    .build();
        } catch (BusinessException ex) {
            return oauthErrorRedirect(ex.errorCode(), ex.getMessage());
        }
    }

    @PostMapping("/jssdk/signature")
    @PreAuthorize("hasAuthority('wecom:sidebar:view')")
    public ApiResponse<WecomJsSdkSignatureResponse> signature(
            @Valid @RequestBody WecomJsSdkSignatureRequest body, HttpServletRequest request) {
        long tenantId = SecuritySupport.requireCurrentUser().tenantId();
        String url = body.url();
        WecomJsSdkSignatureResponse.Signature config = jsSdkService.sign(tenantId, url, false);
        WecomJsSdkSignatureResponse.Signature agentConfig = jsSdkService.sign(tenantId, url, true);
        WecomJsSdkSignatureResponse response = new WecomJsSdkSignatureResponse(officialProperties.corpId(),
                sidebarProperties.agentId(), url, config, agentConfig);
        return ApiResponse.ok(response, requestId(request));
    }

    @GetMapping("/sidebar/context")
    @PreAuthorize("hasAuthority('wecom:sidebar:view')")
    public ApiResponse<WecomSidebarContextResponse> context(
            @RequestParam(required = false) @Size(max = 192) String externalUserid, HttpServletRequest request) {
        return ApiResponse.ok(contextService.context(SecuritySupport.requireCurrentUser(), externalUserid),
                requestId(request));
    }

    @GetMapping("/sidebar/group-context")
    @PreAuthorize("hasAuthority('wecom:sidebar:view')")
    public ApiResponse<WecomSidebarGroupContextResponse> groupContext(
            @RequestParam(required = false) @Size(max = 192) String chatId, HttpServletRequest request) {
        return ApiResponse.ok(contextService.groupContext(SecuritySupport.requireCurrentUser(), chatId),
                requestId(request));
    }

    @PostMapping("/sidebar/contacts/link")
    @PreAuthorize("hasAuthority('wecom:sidebar:view') and hasAuthority('customer:write')")
    public ApiResponse<WecomSidebarLinkContactResponse> linkContact(
            @Valid @RequestBody WecomSidebarLinkContactRequest body, HttpServletRequest request) {
        return ApiResponse.ok(contextService.linkContact(SecuritySupport.requireCurrentUser(), body),
                requestId(request));
    }

    @PostMapping("/sidebar/materials/{materialId}/media")
    @PreAuthorize("hasAuthority('wecom:sidebar:view') and hasAuthority('material:read')")
    public ApiResponse<WecomSidebarMaterialMediaResponse> uploadMaterialMedia(
            @PathVariable @Min(1) long materialId, HttpServletRequest request) {
        return ApiResponse.ok(materialMediaService.uploadMaterialMedia(SecuritySupport.requireCurrentUser(), materialId),
                requestId(request));
    }

    private ResponseEntity<Void> oauthErrorRedirect(ErrorCode errorCode, String rawMessage) {
        String sidebarUrl = oauthService.sidebarUrl();
        HttpUrl parsed = sidebarUrl == null || sidebarUrl.isBlank() ? null : HttpUrl.parse(sidebarUrl.strip());
        String safeMessage = sanitizeOAuthMessage(rawMessage);
        if (parsed == null) {
            throw new BusinessException(errorCode, safeMessage);
        }
        URI target = parsed.newBuilder()
                .setQueryParameter("oauth_error", errorCode.name())
                .setQueryParameter("oauth_message", safeMessage)
                .build()
                .uri();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(target)
                .build();
    }

    private String sanitizeOAuthMessage(String rawMessage) {
        if (rawMessage == null) {
            return DEFAULT_OAUTH_ERROR_MESSAGE;
        }
        StringBuilder cleaned = new StringBuilder(Math.min(rawMessage.length(), 120));
        for (int i = 0; i < rawMessage.length(); i++) {
            char c = rawMessage.charAt(i);
            if (!Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        String result = cleaned.toString().strip();
        if (result.isEmpty()) {
            return DEFAULT_OAUTH_ERROR_MESSAGE;
        }
        return result.length() > 120 ? result.substring(0, 120) : result;
    }

    private ResponseCookie cookie(String name, String value, boolean httpOnly) {
        return ResponseCookie.from(name, value).httpOnly(httpOnly)
                .secure(authProperties.cookieSecure()).sameSite("Lax").path("/")
                .maxAge(Duration.ofSeconds(authProperties.jwtTtlSeconds())).build();
    }
}
