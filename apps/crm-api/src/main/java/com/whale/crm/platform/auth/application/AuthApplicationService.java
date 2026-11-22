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
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 认证应用服务：登录签发（JWT + 服务端会话）、登出吊销、当前主体查询。
 * 账号或密码失败统一为 401「用户名或密码错误」，不区分用户不存在/密码错误/账号停用，防止枚举。
 * 验证码、限流和保护服务异常使用独立稳定错误码。
 */
@Service
public class AuthApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);

    /** 用户不存在/停用/无哈希时的有效 60 位 BCrypt 哑哈希：拉平校验耗时，避免时间侧信道探测。 */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5V5T9R1P3K7M2N4Q6S8U0W2Y4a6c8e0";

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
        return login(username, password, captchaId, captchaAnswer, null, clientIp, userAgent);
    }

    @Transactional
    public AuthResult login(String username, String password, String captchaId, String captchaAnswer,
                            String tenantCode, String clientIp, String userAgent) {
        String normalizedTenantCode = tenantCode == null || tenantCode.isBlank() ? null : tenantCode.strip();
        loginAttemptService.checkIpAllowed(clientIp);
        try {
            captchaService.verify(captchaId, captchaAnswer);
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.VALIDATION_FAILED) {
                try {
                    loginAttemptService.recordCaptchaFailure(clientIp);
                } finally {
                    recordCaptchaFailureAudit(username, normalizedTenantCode);
                }
            }
            throw ex;
        }

        if (normalizedTenantCode != null) {
            List<Long> tenantIds = userRepository.findActiveTenantIdsByOrgCode(normalizedTenantCode);
            if (tenantIds.size() != 1) {
                String attemptScope = "org:" + normalizedTenantCode;
                loginAttemptService.checkAllowed(attemptScope, username, clientIp);
                passwordEncoder.matches(password, DUMMY_HASH);
                try {
                    loginAttemptService.recordFailure(attemptScope, username, clientIp);
                } finally {
                    log.warn("登录组织编码无效或存在歧义，拒绝认证 matchedOrgs={}", tenantIds.size());
                }
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
            }
            long resolvedTenantId = tenantIds.getFirst();
            String attemptScope = tenantAttemptScope(resolvedTenantId);
            loginAttemptService.checkAllowed(attemptScope, username, clientIp);
            Optional<SysUser> found = userRepository.findByTenantAndUsernameForAuth(resolvedTenantId, username);
            return verifyPasswordAndIssueSession(found, resolvedTenantId, attemptScope, username, password,
                    clientIp, userAgent);
        }

        List<SysUser> candidates = userRepository.findCandidatesByUsernameForAuth(username);
        if (candidates.size() > 1) {
            String attemptScope = "ambiguous";
            loginAttemptService.checkAllowed(attemptScope, username, clientIp);
            passwordEncoder.matches(password, DUMMY_HASH);
            try {
                loginAttemptService.recordFailure(attemptScope, username, clientIp);
            } finally {
                log.warn("登录名跨租户重复且未提供组织编码，拒绝登录 candidateCount={}", candidates.size());
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "存在多个组织账号，请填写组织编码",
                    List.of(new ApiResponse.ErrorDetail("tenantCode", "请填写组织编码")));
        }
        Optional<SysUser> found = candidates.stream().findFirst();
        long auditTenantId = found.map(SysUser::getTenantId).orElse(TenantContext.DEFAULT_TENANT_ID);
        String attemptScope = tenantAttemptScope(auditTenantId);
        loginAttemptService.checkAllowed(attemptScope, username, clientIp);
        return verifyPasswordAndIssueSession(found, auditTenantId, attemptScope, username, password,
                clientIp, userAgent);
    }

    private static String tenantAttemptScope(long tenantId) {
        return "tenant:" + tenantId;
    }

    private AuthResult verifyPasswordAndIssueSession(Optional<SysUser> found, long auditTenantId, String attemptScope,
                                                     String username, String password, String clientIp,
                                                     String userAgent) {
        String targetHash = found.map(SysUser::getPasswordHash)
                .filter(hash -> !hash.isBlank())
                .orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(password, targetHash);
        boolean authenticated = found.isPresent()
                && found.get().isActive()
                && found.get().getPasswordHash() != null
                && passwordMatches;
        if (!authenticated) {
            try {
                loginAttemptService.recordFailure(attemptScope, username, clientIp);
            } finally {
                auditService.recordIndependent(
                        auditTenantId,
                        found.map(SysUser::getId).orElse(null),
                        AuditService.ACTION_LOGIN_FAILED, "USER",
                        found.map(u -> Long.toString(u.getId())).orElse("-"),
                        null, Map.of("username", username), "凭证校验失败");
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        SysUser user = found.get();
        loginAttemptService.recordSuccess(attemptScope, username);
        return issueVerifiedSession(user, clientIp, userAgent);
    }

    private void recordCaptchaFailureAudit(String username, String normalizedTenantCode) {
        if (normalizedTenantCode != null) {
            List<Long> tenantIds = userRepository.findActiveTenantIdsByOrgCode(normalizedTenantCode);
            if (tenantIds.size() == 1) {
                auditService.recordIndependent(tenantIds.getFirst(), null,
                        AuditService.ACTION_LOGIN_FAILED, "USER", "-", null,
                        Map.of("username", username, "tenantCode", normalizedTenantCode), "验证码校验失败");
            } else {
                log.warn("登录验证码校验失败但组织编码无效或存在歧义，跳过租户审计落库 matchedOrgs={}", tenantIds.size());
            }
            return;
        }
        List<SysUser> candidates = userRepository.findCandidatesByUsernameForAuth(username);
        if (candidates.size() == 1) {
            SysUser candidate = candidates.getFirst();
            auditService.recordIndependent(candidate.getTenantId(), candidate.getId(),
                    AuditService.ACTION_LOGIN_FAILED, "USER", Long.toString(candidate.getId()), null,
                    Map.of("username", username), "验证码校验失败");
        } else if (candidates.isEmpty()) {
            auditService.recordIndependent(TenantContext.DEFAULT_TENANT_ID, null,
                    AuditService.ACTION_LOGIN_FAILED, "USER", "-", null,
                    Map.of("username", username), "验证码校验失败");
        } else {
            log.warn("登录验证码校验失败且用户名跨租户重复，跳过租户审计落库 candidateCount={}", candidates.size());
        }
    }

    @Transactional
    public AuthResult loginWithVerifiedIdentity(long userId, String clientIp, String userAgent) {
        SysUser user = userRepository.findByIdForAuth(userId)
                .filter(SysUser::isActive)
                .filter(candidate -> "EMPLOYEE".equals(candidate.getUserType()))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "企微身份未关联有效 CRM 员工"));
        return issueVerifiedSession(user, clientIp, userAgent);
    }

    private AuthResult issueVerifiedSession(SysUser user, String clientIp, String userAgent) {
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
            loginSessionRepository.revokeByTokenHash(currentUser.tenantId(), currentUser.userId(),
                    JwtAuthenticationFilter.sha256Hex(token), Instant.now());
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
