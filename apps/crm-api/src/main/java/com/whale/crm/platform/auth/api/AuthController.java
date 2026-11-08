package com.whale.crm.platform.auth.api;

import com.whale.crm.platform.auth.api.dto.AuthUserResponse;
import com.whale.crm.platform.auth.api.dto.LoginRequest;
import com.whale.crm.platform.auth.api.dto.CaptchaResponse;
import com.whale.crm.platform.auth.application.AuthApplicationService;
import com.whale.crm.platform.auth.application.CaptchaService;
import com.whale.crm.platform.auth.application.AuthApplicationService.AuthResult;
import com.whale.crm.platform.security.AuthSecurityProperties;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 认证接口。登录成功设置 CRM_TOKEN（HttpOnly JWT）与 CRM_CSRF（可读随机串）双 Cookie；
 * 登出吊销服务端会话并清 Cookie。令牌与 CSRF 值不进入任何响应体。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthApplicationService authService;
    private final AuthSecurityProperties properties;
    private final CaptchaService captchaService;
    private final com.whale.crm.platform.auth.application.LoginAttemptService loginAttemptService;

    public AuthController(AuthApplicationService authService, AuthSecurityProperties properties,
                          CaptchaService captchaService,
                          com.whale.crm.platform.auth.application.LoginAttemptService loginAttemptService) {
        this.authService = authService;
        this.properties = properties;
        this.captchaService = captchaService;
        this.loginAttemptService = loginAttemptService;
    }

    @GetMapping("/captcha")
    public ResponseEntity<ApiResponse<CaptchaResponse>> captcha(HttpServletRequest request) {
        loginAttemptService.checkCaptchaAllowed(clientIp(request));
        CaptchaService.CaptchaChallenge challenge = captchaService.issue();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(ApiResponse.ok(new CaptchaResponse(challenge.captchaId(), challenge.imageBase64(),
                        challenge.expiresIn()), requestId(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthUserResponse>> login(@Valid @RequestBody LoginRequest body,
                                                               HttpServletRequest request) {
        AuthResult result = authService.login(body.username(), body.password(), body.captchaId(), body.captchaAnswer(),
                clientIp(request), request.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, tokenCookie(result.token()).toString())
                .header(HttpHeaders.SET_COOKIE, csrfCookie(result.csrfToken()).toString())
                .body(ApiResponse.ok(result.user(), requestId(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        CurrentUser currentUser = SecuritySupport.requireCurrentUser();
        authService.logout(currentUser, readTokenCookie(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearedCookie(properties.tokenCookieName(), true).toString())
                .header(HttpHeaders.SET_COOKIE, clearedCookie(properties.csrfCookieName(), false).toString())
                .body(ApiResponse.ok(null, requestId(request)));
    }

    @GetMapping("/me")
    public ApiResponse<AuthUserResponse> me(HttpServletRequest request) {
        return ApiResponse.ok(authService.me(SecuritySupport.requireCurrentUser()), requestId(request));
    }

    private ResponseCookie tokenCookie(String token) {
        return ResponseCookie.from(properties.tokenCookieName(), token)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(properties.jwtTtlSeconds()))
                .build();
    }

    private ResponseCookie csrfCookie(String csrfToken) {
        return ResponseCookie.from(properties.csrfCookieName(), csrfToken)
                .httpOnly(false)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(properties.jwtTtlSeconds()))
                .build();
    }

    private ResponseCookie clearedCookie(String name, boolean httpOnly) {
        return ResponseCookie.from(name, "")
                .httpOnly(httpOnly)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    private String readTokenCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (properties.tokenCookieName().equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
