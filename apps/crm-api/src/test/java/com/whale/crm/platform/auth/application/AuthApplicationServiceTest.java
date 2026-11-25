package com.whale.crm.platform.auth.application;

import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.auth.domain.LoginSessionRepository;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.security.JwtAuthenticationFilter;
import com.whale.crm.platform.security.JwtService;
import com.whale.crm.platform.security.PasswordPolicyProperties;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthApplicationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private InstitutionAccessSupport institutionAccess;
    @Mock
    private LoginSessionRepository loginSessionRepository;
    @Mock
    private JwtService jwtService;
    @Mock
    private AuditService auditService;
    @Mock
    private CaptchaService captchaService;
    @Mock
    private LoginAttemptService loginAttemptService;

    private PasswordEncoder passwordEncoder;
    private PasswordPolicyService passwordPolicyService;
    private AuthApplicationService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = spy(new BCryptPasswordEncoder());
        passwordPolicyService = new PasswordPolicyService(PasswordPolicyProperties.defaultPolicy());
        service = new AuthApplicationService(userRepository, currentUserService, institutionAccess,
                loginSessionRepository, jwtService, auditService, passwordEncoder, captchaService,
                loginAttemptService, passwordPolicyService);
    }

    @Test
    void disabledAndNullHashAndMissingUser_allExecuteDummyBcryptMatch() {
        SysUser disabled = new SysUser();
        disabled.setId(10L);
        disabled.setTenantId(1L);
        disabled.setUsername("disabled_user");
        disabled.setPasswordHash(null);
        disabled.setStatus(SysUser.STATUS_DISABLED);
        when(userRepository.findCandidatesByUsernameForAuth("disabled_user")).thenReturn(List.of(disabled));

        assertThatThrownBy(() -> service.login("disabled_user", "WrongPass!1", "cid", "3", "127.0.0.1", "UA"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(eq("WrongPass!1"), hashCaptor.capture());
        String dummyHash = hashCaptor.getValue();
        assertThat(dummyHash).hasSize(60).startsWith("$2a$10$");
        assertThat(new BCryptPasswordEncoder().matches("WrongPass!1", dummyHash)).isFalse();
    }

    @Test
    void collidingUsernameWithoutTenantCode_returnsValidationFailedWithoutTenantOneAudit() {
        SysUser u1 = new SysUser();
        u1.setId(1L);
        u1.setTenantId(1L);
        u1.setUsername("admin");
        SysUser u2 = new SysUser();
        u2.setId(2L);
        u2.setTenantId(2L);
        u2.setUsername("admin");
        when(userRepository.findCandidatesByUsernameForAuth("admin")).thenReturn(List.of(u1, u2));

        assertThatThrownBy(() -> service.login("admin", "Pass@1234", "cid", "3", null, "192.0.2.10", "UA"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verify(passwordEncoder).matches(eq("Pass@1234"), anyString());
        verify(loginAttemptService).recordFailure("ambiguous", "admin", "192.0.2.10");
        verifyNoInteractions(auditService);
    }

    @Test
    void captchaFailure_recordsIpOnlyAndSkipsAuditWhenTenantIsUnknownOrAmbiguous() {
        doThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "验证码错误"))
                .when(captchaService).verify("cid", "bad");
        when(userRepository.findActiveTenantIdsByOrgCode("UNKNOWN_ORG")).thenReturn(List.of());

        assertThatThrownBy(() -> service.login("alice", "Pass@1234", "cid", "bad", "UNKNOWN_ORG", "192.0.2.20", "UA"))
                .isInstanceOf(BusinessException.class);

        verify(loginAttemptService).checkIpAllowed("192.0.2.20");
        verify(loginAttemptService).recordCaptchaFailure("192.0.2.20");
        verify(loginAttemptService, never()).recordFailure(anyString(), anyString(), anyString());
        verifyNoInteractions(auditService);
    }

    @Test
    void captchaRedisOutage_doesNotRecordCaptchaFailureOrWriteMisleadingAudit() {
        doThrow(new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "登录保护服务暂不可用，请稍后重试"))
                .when(captchaService).verify("cid", "3");

        assertThatThrownBy(() -> service.login("alice", "Pass@1234", "cid", "3", "WHALE", "192.0.2.22", "UA"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.AUTH_PROTECTION_UNAVAILABLE);

        verify(loginAttemptService).checkIpAllowed("192.0.2.22");
        verify(loginAttemptService, never()).recordCaptchaFailure(anyString());
        verifyNoInteractions(auditService);
    }

    @Test
    void captchaFailure_withValidTenantCode_auditsUnderResolvedTenantId() {
        doThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "验证码错误"))
                .when(captchaService).verify("cid", "bad");
        when(userRepository.findActiveTenantIdsByOrgCode("ORG_TWO")).thenReturn(List.of(2L));

        assertThatThrownBy(() -> service.login("alice", "Pass@1234", "cid", "bad", "ORG_TWO", "192.0.2.21", "UA"))
                .isInstanceOf(BusinessException.class);

        verify(auditService).recordIndependent(eq(2L), eq(null), eq(AuditService.ACTION_LOGIN_FAILED),
                eq("USER"), eq("-"), eq(null), any(), eq("验证码校验失败"));
    }

    @Test
    void logout_scopesRevocationToCurrentUserTenantAndId() {
        CurrentUser currentUser = new CurrentUser(42L, 3L, "alice", "Alice", "EMPLOYEE",
                null, Set.of("MANAGER"), Set.of("customer:read"), Set.of());

        service.logout(currentUser, "jwt-token-value");

        verify(loginSessionRepository).revokeByTokenHash(eq(3L), eq(42L),
                eq(JwtAuthenticationFilter.sha256Hex("jwt-token-value")), any(Instant.class));
        verify(auditService).record(eq(3L), eq(42L), eq(AuditService.ACTION_LOGOUT), eq("USER"),
                eq("42"), eq(null), any(), eq(null));
    }

    @Test
    void loginWithTenantCode_unknownOrgReturnsUnauthorizedAfterDummyBcrypt() {
        when(userRepository.findActiveTenantIdsByOrgCode("NO_ORG")).thenReturn(List.of());

        assertThatThrownBy(() -> service.login("alice", "Pass@1234", "cid", "3", "NO_ORG", "192.0.2.30", "UA"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(passwordEncoder).matches(eq("Pass@1234"), anyString());
        verify(loginAttemptService).checkAllowed("org:NO_ORG", "alice", "192.0.2.30");
        verify(loginAttemptService).recordFailure("org:NO_ORG", "alice", "192.0.2.30");
        verifyNoInteractions(auditService);
        verify(userRepository, never()).findByTenantAndUsernameForAuth(anyLong(), anyString());
    }

    @Test
    void loginScopesAttemptChecksFailuresAndSuccessByResolvedTenant() {
        SysUser tenant2User = new SysUser();
        tenant2User.setId(22L);
        tenant2User.setTenantId(2L);
        tenant2User.setUsername("alice");
        tenant2User.setDisplayName("Alice T2");
        tenant2User.setUserType("EMPLOYEE");
        tenant2User.setStatus(SysUser.STATUS_ACTIVE);
        tenant2User.setPasswordHash(new BCryptPasswordEncoder().encode("Correct@123"));

        when(userRepository.findActiveTenantIdsByOrgCode("ORG2")).thenReturn(List.of(2L));
        when(userRepository.findByTenantAndUsernameForAuth(2L, "alice")).thenReturn(Optional.of(tenant2User));
        when(jwtService.issue(eq(22L), eq("alice"), anyString())).thenReturn("jwt-t2");
        when(jwtService.ttlSeconds()).thenReturn(7200L);
        when(currentUserService.toCurrentUser(tenant2User)).thenReturn(
                new CurrentUser(22L, 2L, "alice", "Alice T2", "EMPLOYEE", null, Set.of(), Set.of(), Set.of()));
        when(institutionAccess.institutionRoles(eq(2L), any())).thenReturn(Map.of());

        service.login("alice", "Correct@123", "cid", "3", "ORG2", "192.0.2.50", "UA");

        verify(loginAttemptService).checkAllowed("tenant:2", "alice", "192.0.2.50");
        verify(loginAttemptService).recordSuccess("tenant:2", "alice");
        verify(loginAttemptService, never()).recordSuccess("tenant:1", "alice");
        verify(loginAttemptService, never()).recordSuccess("alice");
    }

    @Test
    void changePassword_successUpdatesHashRevokesAllSessionsAndAuditsWithoutSecrets() {
        CurrentUser currentUser = new CurrentUser(42L, 3L, "alice", "Alice", "EMPLOYEE",
                null, Set.of("MANAGER"), Set.of("customer:read"), Set.of());
        SysUser user = new SysUser();
        user.setId(42L);
        user.setTenantId(3L);
        user.setUsername("alice");
        user.setDisplayName("Alice");
        user.setStatus(SysUser.STATUS_ACTIVE);
        user.setVersion(5L);
        user.setPasswordHash(new BCryptPasswordEncoder().encode("OldPass@2026"));

        when(userRepository.findById(3L, 42L)).thenReturn(Optional.of(user));
        when(userRepository.updatePasswordHash(eq(3L), eq(42L), eq(5L), anyString())).thenReturn(1);
        when(loginSessionRepository.revokeAllByUserId(eq(3L), eq(42L), any(Instant.class))).thenReturn(2);

        service.changePassword(currentUser, "OldPass@2026", "NewPass@2026", "NewPass@2026", "192.0.2.60");

        ArgumentCaptor<String> encodedCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).updatePasswordHash(eq(3L), eq(42L), eq(5L), encodedCaptor.capture());
        assertThat(new BCryptPasswordEncoder().matches("NewPass@2026", encodedCaptor.getValue())).isTrue();
        verify(loginSessionRepository).revokeAllByUserId(eq(3L), eq(42L), any(Instant.class));
        verify(loginAttemptService).recordPasswordChangeSuccess("tenant:3", 42L);

        ArgumentCaptor<Object> snapshotCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditService).record(eq(3L), eq(42L), eq(AuditService.ACTION_PASSWORD_CHANGE), eq("USER"),
                eq("42"), eq(null), snapshotCaptor.capture(), eq("用户主动修改登录密码"));
        String snapshot = String.valueOf(snapshotCaptor.getValue());
        assertThat(snapshot)
                .doesNotContain("OldPass@2026")
                .doesNotContain("NewPass@2026")
                .doesNotContain("$2a$")
                .doesNotContain("$2b$");
    }

    @Test
    void changePassword_wrongCurrentPasswordRecordsFailureAndIndependentAudit() {
        CurrentUser currentUser = new CurrentUser(42L, 3L, "alice", "Alice", "EMPLOYEE",
                null, Set.of("MANAGER"), Set.of("customer:read"), Set.of());
        SysUser user = new SysUser();
        user.setId(42L);
        user.setTenantId(3L);
        user.setUsername("alice");
        user.setStatus(SysUser.STATUS_ACTIVE);
        user.setVersion(1L);
        user.setPasswordHash(new BCryptPasswordEncoder().encode("OldPass@2026"));

        when(userRepository.findById(3L, 42L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword(
                currentUser, "WrongPass@2026", "NewPass@2026", "NewPass@2026", "192.0.2.61"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(be.details()).anySatisfy(d -> assertThat(d.field()).isEqualTo("currentPassword"));
                });

        verify(loginAttemptService).recordPasswordChangeFailure("tenant:3", 42L, "192.0.2.61");
        verify(auditService).recordIndependent(eq(3L), eq(42L), eq(AuditService.ACTION_PASSWORD_CHANGE_FAILED),
                eq("USER"), eq("42"), eq(null), any(), eq("原密码校验失败"));
        verify(userRepository, never()).updatePasswordHash(anyLong(), anyLong(), anyLong(), anyString());
        verify(loginSessionRepository, never()).revokeAllByUserId(anyLong(), anyLong(), any());
    }

    @Test
    void changePassword_weakNewPasswordDoesNotConsumeOldPasswordFailureBudget() {
        CurrentUser currentUser = new CurrentUser(42L, 3L, "alice", "Alice", "EMPLOYEE",
                null, Set.of("MANAGER"), Set.of("customer:read"), Set.of());

        assertThatThrownBy(() -> service.changePassword(
                currentUser, "OldPass@2026", "weakpass", "weakpass", "192.0.2.62"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verify(loginAttemptService).checkPasswordChangeAllowed("tenant:3", 42L, "192.0.2.62");
        verify(loginAttemptService, never()).recordPasswordChangeFailure(anyString(), anyLong(), anyString());
        verifyNoInteractions(userRepository, auditService);
    }

    @Test
    void changePassword_optimisticLockConflictThrows409() {
        CurrentUser currentUser = new CurrentUser(42L, 3L, "alice", "Alice", "EMPLOYEE",
                null, Set.of("MANAGER"), Set.of("customer:read"), Set.of());
        SysUser user = new SysUser();
        user.setId(42L);
        user.setTenantId(3L);
        user.setUsername("alice");
        user.setStatus(SysUser.STATUS_ACTIVE);
        user.setVersion(5L);
        user.setPasswordHash(new BCryptPasswordEncoder().encode("OldPass@2026"));

        when(userRepository.findById(3L, 42L)).thenReturn(Optional.of(user));
        when(userRepository.updatePasswordHash(eq(3L), eq(42L), eq(5L), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.changePassword(
                currentUser, "OldPass@2026", "NewPass@2026", "NewPass@2026", "192.0.2.63"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(loginSessionRepository, never()).revokeAllByUserId(anyLong(), anyLong(), any());
    }
}
