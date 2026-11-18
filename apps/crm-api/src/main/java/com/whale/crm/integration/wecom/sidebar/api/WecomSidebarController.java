package com.whale.crm.integration.wecom.sidebar.api;

import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomJsSdkSignatureRequest;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomJsSdkSignatureResponse;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarContextResponse;
import com.whale.crm.integration.wecom.sidebar.application.WecomJsSdkService;
import com.whale.crm.integration.wecom.sidebar.application.WecomOAuthService;
import com.whale.crm.integration.wecom.sidebar.application.WecomSidebarContextService;
import com.whale.crm.integration.wecom.api.WecomBaseController;
import com.whale.crm.platform.auth.application.AuthApplicationService.AuthResult;
import com.whale.crm.platform.security.AuthSecurityProperties;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final WecomOAuthService oauthService;
    private final WecomJsSdkService jsSdkService;
    private final WecomSidebarContextService contextService;
    private final WecomOfficialProperties officialProperties;
    private final com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties sidebarProperties;
    private final AuthSecurityProperties authProperties;

    public WecomSidebarController(WecomOAuthService oauthService, WecomJsSdkService jsSdkService,
                                  WecomSidebarContextService contextService,
                                  WecomOfficialProperties officialProperties,
                                  com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties sidebarProperties,
                                  AuthSecurityProperties authProperties) {
        this.oauthService = oauthService;
        this.jsSdkService = jsSdkService;
        this.contextService = contextService;
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
    public ResponseEntity<Void> callback(@RequestParam @NotBlank @Size(max = 512) String code,
                                         @RequestParam @NotBlank @Size(max = 128) String state,
                                         HttpServletRequest request) {
        AuthResult result = oauthService.callback(code, state, request.getRemoteAddr(),
                request.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthService.sidebarUrl()))
                .header(HttpHeaders.SET_COOKIE, cookie(authProperties.tokenCookieName(), result.token(), true).toString())
                .header(HttpHeaders.SET_COOKIE, cookie(authProperties.csrfCookieName(), result.csrfToken(), false).toString())
                .build();
    }

    @PostMapping("/jssdk/signature")
    @PreAuthorize("hasAuthority('wecom:sidebar:view')")
    public ApiResponse<WecomJsSdkSignatureResponse> signature(
            @Valid @RequestBody WecomJsSdkSignatureRequest body, HttpServletRequest request) {
        String url = body.url();
        WecomJsSdkSignatureResponse response = new WecomJsSdkSignatureResponse(officialProperties.corpId(),
                sidebarProperties.agentId(), url, jsSdkService.sign(url, false), jsSdkService.sign(url, true));
        return ApiResponse.ok(response, requestId(request));
    }

    @GetMapping("/sidebar/context")
    @PreAuthorize("hasAuthority('wecom:sidebar:view')")
    public ApiResponse<WecomSidebarContextResponse> context(
            @RequestParam(required = false) @Size(max = 192) String externalUserid, HttpServletRequest request) {
        return ApiResponse.ok(contextService.context(SecuritySupport.requireCurrentUser(), externalUserid),
                requestId(request));
    }

    private ResponseCookie cookie(String name, String value, boolean httpOnly) {
        return ResponseCookie.from(name, value).httpOnly(httpOnly)
                .secure(authProperties.cookieSecure()).sameSite("Lax").path("/")
                .maxAge(Duration.ofSeconds(authProperties.jwtTtlSeconds())).build();
    }
}
