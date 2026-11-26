package com.whale.crm.platform.user.application;

import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.auth.domain.LoginSessionRepository;
import com.whale.crm.platform.user.api.dto.AssignRolesRequest;
import com.whale.crm.platform.user.api.dto.CreateUserRequest;
import com.whale.crm.platform.user.api.dto.UpdateUserRequest;
import com.whale.crm.platform.user.domain.DataScopeGrant;
import com.whale.crm.platform.user.domain.DataScopeRepository;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysRole;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserApplicationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private DataScopeRepository dataScopeRepository;
    @Mock
    private LoginSessionRepository loginSessionRepository;
    @Mock
    private AuditService auditService;

    private PasswordEncoder passwordEncoder;
    private UserApplicationService service;
    private Validator validator;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        service = new UserApplicationService(userRepository, roleRepository, dataScopeRepository,
                loginSessionRepository, auditService, passwordEncoder);
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void updateUserRequest_rejectsWhitespaceOnlyPasswordWhileAllowingNull() {
        assertThat(validator.validate(new UpdateUserRequest("正常名字", null, null, 0L))).isEmpty();
        assertThat(validator.validate(new UpdateUserRequest("正常名字", null, "        ", 0L))).isNotEmpty();
        assertThat(validator.validate(new UpdateUserRequest("正常名字", null, "\t\t\t\t\t\t\t\t", 0L))).isNotEmpty();
        assertThat(validator.validate(new UpdateUserRequest("正常名字", null, "\n\n\n\n\n\n\n\n", 0L))).isNotEmpty();
        assertThat(validator.validate(new UpdateUserRequest("正常名字", null, "ValidPass1\n", 0L))).isEmpty();
    }

    @Test
    void nonAdminCannotAssignAdminOrElevatedRoleOnCreateOrAssign() {
        SysRole adminRole = role(1L, "ADMIN");
        SysRole financeRole = role(6L, "FINANCE");
        when(userRepository.existsByUsername(1L, "new_user")).thenReturn(false);
        when(userRepository.findDefaultOrgId(1L)).thenReturn(Optional.of(1L));
        when(roleRepository.findExistingRoleIds(1L, List.of(1L))).thenReturn(List.of(1L));
        when(roleRepository.findActiveRoleCodes(1L, 20L)).thenReturn(List.of("CUSTOM_USER_ADMIN"));
        when(roleRepository.findAllRoles(1L)).thenReturn(List.of(adminRole, financeRole));
        when(roleRepository.findActivePermissionCodes(1L, 20L)).thenReturn(List.of("user:read", "user:write"));

        assertThatThrownBy(() -> service.create(1L, 20L,
                new CreateUserRequest("new_user", "新用户", "Pass@1234", "EMPLOYEE", null, List.of(1L))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        SysUser target = user(50L, "target_user");
        when(userRepository.findById(1L, 50L)).thenReturn(Optional.of(target));
        when(roleRepository.findActiveRoleCodes(1L, 50L)).thenReturn(List.of());
        when(roleRepository.findExistingRoleIds(1L, List.of(6L))).thenReturn(List.of(6L));
        when(roleRepository.findPermissionCodesByRoleId(1L, 6L)).thenReturn(List.of("refund:approve"));

        assertThatThrownBy(() -> service.assignRoles(1L, 20L, 50L, new AssignRolesRequest(List.of(6L))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(roleRepository, never()).replaceUserRoles(anyLong(), anyLong(), any());
    }

    @Test
    void nonAdminCannotModifyOrDisableAdminAccount() {
        SysUser adminUser = user(1L, "admin");
        when(userRepository.findById(1L, 1L)).thenReturn(Optional.of(adminUser));
        when(roleRepository.findActiveRoleCodes(1L, 20L)).thenReturn(List.of("CUSTOM_USER_ADMIN"));
        when(roleRepository.findActiveRoleCodes(1L, 1L)).thenReturn(List.of("ADMIN"));

        assertThatThrownBy(() -> service.update(1L, 20L, 1L,
                new UpdateUserRequest("篡改", null, "NewPass@1234", 0L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThatThrownBy(() -> service.assignRoles(1L, 20L, 1L, new AssignRolesRequest(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThatThrownBy(() -> service.disable(1L, 20L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void createAndAssignRoles_deduplicateRoleIds() {
        when(userRepository.existsByUsername(1L, "dedup_user")).thenReturn(false);
        when(userRepository.findDefaultOrgId(1L)).thenReturn(Optional.of(1L));
        when(roleRepository.findExistingRoleIds(1L, List.of(4L))).thenReturn(List.of(4L));
        when(roleRepository.findActiveRoleCodes(1L, 1L)).thenReturn(List.of("ADMIN"));
        doAnswer(invocation -> {
            invocation.getArgument(0, SysUser.class).setId(99L);
            return null;
        }).when(userRepository).insert(any(SysUser.class));
        SysUser persisted = user(99L, "dedup_user");
        when(userRepository.findById(1L, 99L)).thenReturn(Optional.of(persisted));

        service.create(1L, 1L, new CreateUserRequest("dedup_user", "去重", "Pass@1234",
                "EMPLOYEE", null, List.of(4L, 4L)));

        verify(roleRepository).replaceUserRoles(eq(1L), eq(99L), eq(List.of(4L)));
    }

    @Test
    void createManager_syncsDefaultSelfCustomerAndAllInstitutionScope() {
        when(userRepository.existsByUsername(1L, "new_mgr")).thenReturn(false);
        when(userRepository.findDefaultOrgId(1L)).thenReturn(Optional.of(1L));
        when(userRepository.teamExists(1L, 1L)).thenReturn(true);
        when(roleRepository.findExistingRoleIds(1L, List.of(4L))).thenReturn(List.of(4L));
        when(roleRepository.findActiveRoleCodes(1L, 1L)).thenReturn(List.of("ADMIN"));
        when(roleRepository.findActiveRoleCodes(1L, 101L)).thenReturn(List.of("MANAGER"));
        doAnswer(invocation -> {
            invocation.getArgument(0, SysUser.class).setId(101L);
            return null;
        }).when(userRepository).insert(any(SysUser.class));
        SysUser persisted = user(101L, "new_mgr");
        persisted.setTeamId(1L);
        when(userRepository.findById(1L, 101L)).thenReturn(Optional.of(persisted));

        service.create(1L, 1L, new CreateUserRequest("new_mgr", "新管家", "Pass@1234",
                "EMPLOYEE", 1L, List.of(4L)));

        verify(dataScopeRepository).replaceResourceScopes(
                eq(1L),
                eq(101L),
                eq(List.of("customer", "institution")),
                eq(List.of(
                        new DataScopeGrant("customer", "SELF", null),
                        new DataScopeGrant("institution", "ALL", null))));
    }

    @Test
    void assignRoles_downgradeAndClearRevokeStaleDataScopes() {
        SysUser target = user(88L, "role_switch");
        target.setTeamId(1L);
        when(userRepository.findById(1L, 88L)).thenReturn(Optional.of(target));
        when(roleRepository.findActiveRoleCodes(1L, 1L)).thenReturn(List.of("ADMIN"));
        when(roleRepository.findActiveRoleCodes(1L, 88L))
                .thenReturn(List.of("ADMIN"), List.of("BUSINESS"), List.of("BUSINESS"), List.of());
        when(roleRepository.findExistingRoleIds(1L, List.of(5L))).thenReturn(List.of(5L));

        service.assignRoles(1L, 1L, 88L, new AssignRolesRequest(List.of(5L)));
        verify(dataScopeRepository).replaceResourceScopes(
                eq(1L),
                eq(88L),
                eq(List.of("customer", "institution")),
                eq(List.of(new DataScopeGrant("institution", "ALL", null))));

        service.assignRoles(1L, 1L, 88L, new AssignRolesRequest(List.of()));
        verify(dataScopeRepository).replaceResourceScopes(
                eq(1L),
                eq(88L),
                eq(List.of("customer", "institution")),
                eq(List.of()));
    }

    @Test
    void updateManagerLeadTeam_replacesCustomerTeamScope_andSameTeamDoesNotTouchScopes() {
        SysUser before = user(77L, "lead_move");
        before.setTeamId(1L);
        SysUser afterMoved = user(77L, "lead_move");
        afterMoved.setTeamId(2L);
        afterMoved.setVersion(1L);
        SysUser afterRenamed = user(77L, "lead_move");
        afterRenamed.setTeamId(2L);
        afterRenamed.setDisplayName("改名主管");
        afterRenamed.setVersion(2L);

        when(userRepository.findById(1L, 77L))
                .thenReturn(Optional.of(before), Optional.of(afterMoved),
                        Optional.of(afterMoved), Optional.of(afterRenamed));
        when(roleRepository.findActiveRoleCodes(1L, 1L)).thenReturn(List.of("ADMIN"));
        when(roleRepository.findActiveRoleCodes(1L, 77L)).thenReturn(List.of("MANAGER_LEAD"));
        when(userRepository.teamExists(1L, 2L)).thenReturn(true);
        when(userRepository.updateProfile(eq(1L), eq(77L), eq(0L), eq("主管"), eq(2L), eq(null))).thenReturn(1);
        when(userRepository.updateProfile(eq(1L), eq(77L), eq(1L), eq("改名主管"), eq(2L), eq(null))).thenReturn(1);

        service.update(1L, 1L, 77L, new UpdateUserRequest("主管", 2L, null, 0L));
        verify(dataScopeRepository).replaceResourceScopes(
                eq(1L),
                eq(77L),
                eq(List.of("customer")),
                eq(List.of(new DataScopeGrant("customer", "TEAM", 2L))));

        service.update(1L, 1L, 77L, new UpdateUserRequest("改名主管", 2L, null, 1L));
        verify(dataScopeRepository).replaceResourceScopes(
                eq(1L),
                eq(77L),
                eq(List.of("customer")),
                eq(List.of(new DataScopeGrant("customer", "TEAM", 2L))));
    }

    @Test
    void nonAdminCannotUpdateAssignRolesOrDisableOutOfScopeUser() {
        SysUser financeUser = user(5L, "finance1");
        when(userRepository.findById(1L, 5L)).thenReturn(Optional.of(financeUser));
        when(roleRepository.findActiveRoleCodes(1L, 20L)).thenReturn(List.of("CUSTOM_USER_ADMIN"));
        when(roleRepository.findActiveRoleCodes(1L, 5L)).thenReturn(List.of("FINANCE"));
        when(roleRepository.findActivePermissionCodes(1L, 20L)).thenReturn(List.of("user:read", "user:write"));
        when(roleRepository.findActivePermissionCodes(1L, 5L))
                .thenReturn(List.of("refund:approve", "settlement:write"));

        assertThatThrownBy(() -> service.update(1L, 20L, 5L,
                new UpdateUserRequest("篡改财务", null, "NewPass@1234", 0L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(userRepository, never()).updateProfile(anyLong(), anyLong(), anyLong(), any(), any(), any());

        assertThatThrownBy(() -> service.assignRoles(1L, 20L, 5L, new AssignRolesRequest(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(roleRepository, never()).replaceUserRoles(anyLong(), anyLong(), any());

        assertThatThrownBy(() -> service.disable(1L, 20L, 5L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(userRepository, never()).updateStatus(anyLong(), anyLong(), any());
    }

    @Test
    void nonAdminCanUpdateAssignRolesAndDisableSubsetUser() {
        SysUser subsetUser = user(30L, "subset_user");
        SysUser updatedUser = user(30L, "subset_user");
        updatedUser.setDisplayName("已更新");
        updatedUser.setVersion(1L);
        SysUser disabledUser = user(30L, "subset_user");
        disabledUser.setStatus(SysUser.STATUS_DISABLED);
        SysRole readerRole = role(12L, "CUSTOM_READER");

        when(userRepository.findById(1L, 30L))
                .thenReturn(Optional.of(subsetUser), Optional.of(updatedUser),
                        Optional.of(updatedUser), Optional.of(updatedUser), Optional.of(disabledUser));
        when(roleRepository.findActiveRoleCodes(1L, 20L)).thenReturn(List.of("CUSTOM_USER_ADMIN"));
        when(roleRepository.findActiveRoleCodes(1L, 30L)).thenReturn(List.of("CUSTOM_READER"));
        when(roleRepository.findActivePermissionCodes(1L, 20L)).thenReturn(List.of("user:read", "user:write"));
        when(roleRepository.findActivePermissionCodes(1L, 30L)).thenReturn(List.of("user:read"));
        when(userRepository.updateProfile(eq(1L), eq(30L), eq(0L), eq("已更新"), eq(null), any()))
                .thenReturn(1);
        when(roleRepository.findExistingRoleIds(1L, List.of(12L))).thenReturn(List.of(12L));
        when(roleRepository.findAllRoles(1L)).thenReturn(List.of(readerRole));
        when(roleRepository.findPermissionCodesByRoleId(1L, 12L)).thenReturn(List.of("user:read"));

        var updateRes = service.update(1L, 20L, 30L, new UpdateUserRequest("已更新", null, "NewPass@1234", 0L));
        assertThat(updateRes.displayName()).isEqualTo("已更新");
        verify(loginSessionRepository).revokeAllByUserId(eq(1L), eq(30L), any());

        service.assignRoles(1L, 20L, 30L, new AssignRolesRequest(List.of(12L)));
        verify(roleRepository).replaceUserRoles(eq(1L), eq(30L), eq(List.of(12L)));
        verify(dataScopeRepository, never()).replaceResourceScopes(anyLong(), anyLong(), any(), any());

        var disableRes = service.disable(1L, 20L, 30L);
        assertThat(disableRes.status()).isEqualTo(SysUser.STATUS_DISABLED);
        verify(userRepository).updateStatus(1L, 30L, SysUser.STATUS_DISABLED);
    }

    private SysRole role(long id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setTenantId(1L);
        role.setRoleCode(code);
        role.setStatus("ACTIVE");
        return role;
    }

    private SysUser user(long id, String username) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(1L);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setUserType("EMPLOYEE");
        user.setStatus(SysUser.STATUS_ACTIVE);
        user.setVersion(0L);
        return user;
    }
}
