package com.whale.crm.platform.security;

import com.whale.crm.platform.auth.domain.LoginSession;
import com.whale.crm.platform.auth.domain.LoginSessionRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * JWT 认证过滤器：从 CRM_TOKEN Cookie（或 Authorization: Bearer）解析令牌，
 * 校验签名/有效期 → 校验服务端会话未吊销 → 实时加载用户/角色/权限/机构映射 → 写入 SecurityContext。
 * 权限代码直接作为 GrantedAuthority（hasAuthority('customer:read')），另附 ROLE_&lt;code&gt; 形式。
 * 任何一步失败均不写入认证信息，由后续授权层统一返回 401。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final LoginSessionRepository loginSessionRepository;
    private final CurrentUserService currentUserService;
    private final AuthSecurityProperties properties;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   LoginSessionRepository loginSessionRepository,
                                   CurrentUserService currentUserService,
                                   AuthSecurityProperties properties) {
        this.jwtService = jwtService;
        this.loginSessionRepository = loginSessionRepository;
        this.currentUserService = currentUserService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            tryAuthenticate(token);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void tryAuthenticate(String token) {
        try {
            Claims claims = jwtService.parse(token);
            long userId = Long.parseLong(claims.getSubject());
            Optional<LoginSession> session = loginSessionRepository.findByTokenHash(sha256Hex(token));
            if (session.isEmpty() || !session.get().isActiveAt(Instant.now())) {
                return;
            }
            Optional<CurrentUser> currentUser = currentUserService.loadById(userId);
            if (currentUser.isEmpty() || currentUser.get().tenantId() != session.get().getTenantId()) {
                return;
            }
            CurrentUser principal = currentUser.get();
            var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
            principal.permissions().forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
            principal.roles().forEach(code -> authorities.add(new SimpleGrantedAuthority("ROLE_" + code)));
            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("访问令牌校验失败: {}", ex.getMessage());
        }
    }

    private String resolveToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (properties.tokenCookieName().equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    public static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("令牌哈希计算失败", ex);
        }
    }
}
