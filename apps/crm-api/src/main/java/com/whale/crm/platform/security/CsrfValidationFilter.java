package com.whale.crm.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * 双提交 CSRF 防护：/api/ 下的写方法（POST/PUT/PATCH/DELETE）要求
 * X-CSRF-Token 请求头与 CRM_CSRF Cookie 值一致；登录接口例外（尚无 Cookie 可校验）。
 * 失败返回 403 + CSRF_TOKEN_INVALID 稳定错误码。
 */
@Component
public class CsrfValidationFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String YOUZAN_WEBHOOK_PATH = "/api/v1/webhooks/youzan";
    private static final String YOUZAN_OFFICIAL_WEBHOOK_PATH = "/api/v1/webhooks/youzan/official";
    private static final String WECOM_CALLBACK_PATH = "/api/v1/integrations/wecom/callback";
    private static final String REFUND_CALLBACK_PREFIX = "/api/v1/integrations/refunds/";
    private static final String REFUND_CALLBACK_SUFFIX = "/callback";
    private static final String API_PREFIX = "/api/";

    private final ObjectMapper objectMapper;
    private final AuthSecurityProperties properties;

    public CsrfValidationFilter(ObjectMapper objectMapper, AuthSecurityProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (SAFE_METHODS.contains(request.getMethod())
                || !path.startsWith(API_PREFIX)
                || LOGIN_PATH.equals(path)
                || YOUZAN_WEBHOOK_PATH.equals(path)
                || YOUZAN_OFFICIAL_WEBHOOK_PATH.equals(path)
                || WECOM_CALLBACK_PATH.equals(path)
                || (path.startsWith(REFUND_CALLBACK_PREFIX) && path.endsWith(REFUND_CALLBACK_SUFFIX))) {
            chain.doFilter(request, response);
            return;
        }
        String headerToken = request.getHeader(properties.csrfHeaderName());
        String cookieToken = readCookie(request, properties.csrfCookieName());
        if (headerToken == null || cookieToken == null || !constantTimeEquals(headerToken, cookieToken)) {
            writeForbidden(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
    private String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (name.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void writeForbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(
                ErrorCode.CSRF_TOKEN_INVALID.name(),
                "CSRF 校验失败，请刷新页面后重试", null, requestId instanceof String s ? s : null)));
    }
}
