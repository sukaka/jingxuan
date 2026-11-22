package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties;
import com.whale.crm.integration.wecom.sidebar.api.WecomSidebarController;
import com.whale.crm.integration.wecom.sidebar.application.WecomJsSdkService;
import com.whale.crm.integration.wecom.sidebar.application.WecomOAuthService;
import com.whale.crm.integration.wecom.sidebar.application.WecomSidebarContextService;
import com.whale.crm.integration.wecom.sidebar.application.WecomSidebarMaterialMediaService;
import com.whale.crm.platform.auth.application.AuthApplicationService;
import com.whale.crm.platform.security.AuthSecurityProperties;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomSidebarControllerTest {
    private WecomOAuthService oauthService;
    private WecomSidebarController controller;

    @BeforeEach
    void setUp() {
        oauthService = mock(WecomOAuthService.class);
        when(oauthService.sidebarUrl()).thenReturn("https://crm.example.com/wecom/sidebar");
        WecomOfficialProperties officialProps = new WecomOfficialProperties(1L, true, "ww-corp", "secret",
                "https://qyapi.weixin.qq.com", "", "", 3000, 3000, 100, 10);
        WecomSidebarProperties sidebarProps = new WecomSidebarProperties(true, 10001L,
                "https://open.weixin.qq.com/connect/oauth2/authorize",
                "https://crm.example.com/api/v1/wecom/oauth/callback",
                "https://crm.example.com/wecom/sidebar",
                List.of("https://crm.example.com"), 300, 60);
        AuthSecurityProperties authProps = new AuthSecurityProperties("test-jwt-secret", 3600,
                "CRM_TOKEN", "CRM_CSRF", "X-CSRF-Token", true);
        controller = new WecomSidebarController(oauthService, mock(WecomJsSdkService.class),
                mock(WecomSidebarContextService.class), mock(WecomSidebarMaterialMediaService.class),
                officialProps, sidebarProps, authProps);
    }

    @Test
    void oauthCallbackRedirectsWithSessionCookiesOnSuccess() {
        when(oauthService.callback("secret-code", "secret-state", "127.0.0.1", "WeCom"))
                .thenReturn(new AuthApplicationService.AuthResult("jwt-token-val", "csrf-token-val",
                        Instant.now().plusSeconds(3600), null));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(HttpHeaders.USER_AGENT, "WeCom");

        ResponseEntity<Void> response = controller.callback("secret-code", "secret-state", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://crm.example.com/wecom/sidebar");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                .hasSize(2)
                .anyMatch(c -> c.startsWith("CRM_TOKEN=jwt-token-val;"))
                .anyMatch(c -> c.startsWith("CRM_CSRF=csrf-token-val;"));
    }

    @Test
    void oauthCallbackRedirectsFriendlyErrorOnBusinessExceptionWithoutLeakingSecretsOrCookies() {
        when(oauthService.callback("secret-code-123", "secret-state-456", "127.0.0.1", null))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "企微成员未唯一关联 CRM 员工，请联系管理员配置"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        ResponseEntity<Void> response = controller.callback("secret-code-123", "secret-state-456", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        String location = response.getHeaders().getLocation().toString();
        assertThat(location).doesNotContain("secret-code-123", "secret-state-456", "CRM_TOKEN", "CRM_CSRF");
        HttpUrl url = HttpUrl.get(location);
        assertThat(url.scheme()).isEqualTo("https");
        assertThat(url.host()).isEqualTo("crm.example.com");
        assertThat(url.encodedPath()).isEqualTo("/wecom/sidebar");
        assertThat(url.queryParameter("oauth_error")).isEqualTo("UNAUTHORIZED");
        assertThat(url.queryParameter("oauth_message")).isEqualTo("企微成员未唯一关联 CRM 员工，请联系管理员配置");
    }

    @Test
    void oauthCallbackRedirectsValidationErrorWhenCodeOrStateMissingOrOversized() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<Void> missingState = controller.callback("some-code", null, request);
        assertThat(missingState.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(missingState.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        HttpUrl url1 = HttpUrl.get(missingState.getHeaders().getLocation().toString());
        assertThat(url1.queryParameter("oauth_error")).isEqualTo("VALIDATION_FAILED");
        assertThat(url1.queryParameter("oauth_message")).isNotBlank();
        assertThat(missingState.getHeaders().getLocation().toString()).doesNotContain("some-code");

        ResponseEntity<Void> blankCode = controller.callback("   ", "valid-state", request);
        assertThat(blankCode.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(HttpUrl.get(blankCode.getHeaders().getLocation().toString()).queryParameter("oauth_error"))
                .isEqualTo("VALIDATION_FAILED");

        ResponseEntity<Void> overlongState = controller.callback("valid-code", "a".repeat(129), request);
        assertThat(overlongState.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(HttpUrl.get(overlongState.getHeaders().getLocation().toString()).queryParameter("oauth_error"))
                .isEqualTo("VALIDATION_FAILED");

        verify(oauthService, never()).callback(anyString(), anyString(), any(), any());
    }

    @Test
    void oauthCallbackSanitizesControlCharsAndTruncatesErrorMessage() {
        when(oauthService.callback(anyString(), anyString(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE,
                        "line1\r\nline2 " + "文".repeat(150)));
        ResponseEntity<Void> response = controller.callback("c", "s", new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        HttpUrl url = HttpUrl.get(response.getHeaders().getLocation().toString());
        assertThat(url.queryParameter("oauth_error")).isEqualTo("AUTH_PROTECTION_UNAVAILABLE");
        String message = url.queryParameter("oauth_message");
        assertThat(message).doesNotContain("\r", "\n");
        assertThat(message.length()).isLessThanOrEqualTo(120);
    }

    @Test
    void oauthCallbackRethrowsWhenSidebarUrlNotConfigured() {
        when(oauthService.sidebarUrl()).thenReturn("");
        assertThatThrownBy(() -> controller.callback(null, null, new MockHttpServletRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
