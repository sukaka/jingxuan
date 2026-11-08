package com.whale.crm.platform.auth.application;

import com.github.f4b6a3.ulid.UlidCreator;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.auth.api.dto.AuthUserResponse;
import com.whale.crm.platform.auth.domain.LoginSession;
import com.whale.crm.platform.auth.domain.LoginSessionRepository;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.security.JwtAuthenticationFilter;
import com.whale.crm.platform.security.JwtService;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 认证应用服务：登录签发（JWT + 服务端会话）、登出吊销、当前主体查询。
 * 账号或密码失败统一为 401「用户名或密码错误」，不区分用户不存在/密码错误/账号停用，防止枚举。
 * 验证码、限流和保护服务异常使用独立稳定错误码。
 */
@Service
public class AuthApplicationService {

    /** 用户不存在时的哑哈希：拉平校验耗时，避免时间侧信道探测用户名是否存在。 */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5B5Q5b5f5b5f5b5f5b5f5b5f5b5f5";

    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final InstitutionAccessSupport institutionAccess;
    private final LoginSessionRepository loginSessionRepository;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaService captchaService;
    private final LoginAttemptService loginAttemptService;

    public AuthApplicationService(UserRepository userRepository,
                                  CurrentUserService currentUserService,
                                  InstitutionAccessSupport institutionAccess,
                                  LoginSessionRepository loginSessionRepository,
                                  JwtService jwtService,
                                  AuditService auditService,
                                  PasswordEncoder passwordEncoder,
                                  CaptchaService captchaService,
                                  LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.institutionAccess = institutionAccess;
        this.loginSessionRepository = loginSessionRepository;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.captchaService = captchaService;
        this.loginAttemptService = loginAttemptService;
    }

    @Transactional
    public AuthResult login(String username, String password, String captchaId, String captchaAnswer,
                            String clientIp, String userAgent) {
        loginAttemptService.checkAllowed(username, clientIp);
        try {
            captchaService.verify(captchaId, captchaAnswer);
        } catch (BusinessException ex) {
            try {
                loginAttemptService.recordFailure(username, clientIp);
            } finally {
                auditService.recordIndependent(TenantContext.DEFAULT_TENANT_ID, null,
                        AuditService.ACTION_LOGIN_FAILED, "USER", "-", null,
                        Map.of("username", username), "验证码校验失败");
            }
            throw ex;
        }

        Optional<SysUser> found = userRepository.findByUsernameForAuth(username);
        boolean authenticated = found
                .map(user -> user.isActive() && user.getPasswordHash() != null
                        && passwordEncoder.matches(password, user.getPasswordHash()))
                .orElseGet(() -> {
                    passwordEncoder.matches(password, DUMMY_HASH);
                    return false;
                });
        if (!authenticated) {
            try {
                loginAttemptService.recordFailure(username, clientIp);
            } finally {
                auditService.recordIndependent(
                        found.map(SysUser::getTenantId).orElse(TenantContext.DEFAULT_TENANT_ID),
                        found.map(SysUser::getId).orElse(null),
                        AuditService.ACTION_LOGIN_FAILED, "USER",
                        found.map(u -> Long.toString(u.getId())).orElse("-"),
                        null, Map.of("username", username), "凭证校验失败");
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        SysUser user = found.get();
        loginAttemptService.recordSuccess(username);
        Instant now = Instant.now();
        String token = jwtService.issue(user.getId(), user.getUsername(), UlidCreator.getUlid().toString());

        LoginSession session = new LoginSession();
        session.setTenantId(user.getTenantId());
        session.setUserId(user.getId());
        session.setTokenHash(JwtAuthenticationFilter.sha256Hex(token));
        session.setClientIp(clientIp);
        session.setUserAgent(userAgent != null && userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent);
        session.setExpiresAt(now.plusSeconds(jwtService.ttlSeconds()));
        loginSessionRepository.insert(session);

        userRepository.touchLastLogin(user.getId(), now);

        auditService.record(user.getTenantId(), user.getId(), AuditService.ACTION_LOGIN, "USER",
                Long.toString(user.getId()), null,
                Map.of("username", user.getUsername(), "displayName", user.getDisplayName()), null);

        CurrentUser currentUser = currentUserService.toCurrentUser(user);
        return new AuthResult(token, UlidCreator.getUlid().toString(), session.getExpiresAt(),
                toResponse(currentUser));
    }

    @Transactional
    public void logout(CurrentUser currentUser, String token) {
        if (token != null) {
            loginSessionRepository.revokeByTokenHash(JwtAuthenticationFilter.sha256Hex(token), Instant.now());
        }
        auditService.record(currentUser.tenantId(), currentUser.userId(), AuditService.ACTION_LOGOUT, "USER",
                Long.toString(currentUser.userId()), null,
                Map.of("username", currentUser.username()), null);
    }

    @Transactional(readOnly = true)
    public AuthUserResponse me(CurrentUser currentUser) {
        return toResponse(currentUser);
    }

    public AuthUserResponse toResponse(CurrentUser currentUser) {
        return new AuthUserResponse(currentUser.userId(), currentUser.username(), currentUser.displayName(),
                currentUser.userType(), currentUser.sortedRoles(), currentUser.sortedPermissions(),
                institutionAccess.institutionRoles(currentUser.tenantId(), currentUser));
    }

    public record AuthResult(String token, String csrfToken, Instant expiresAt, AuthUserResponse user) {
    }
}
