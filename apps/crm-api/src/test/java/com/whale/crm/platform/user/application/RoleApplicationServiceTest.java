package com.whale.crm.platform.user.application;

import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.user.api.dto.UpdateRolePermissionsRequest;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysRole;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleApplicationServiceTest {

    @Mock
    private RoleRepository roleRepository;
    @Mock
    private AuditService auditService;

    private RoleApplicationService service;
    private Validator validator;

    @BeforeEach
    void setUp() {
        service = new RoleApplicationService(roleRepository, auditService);
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void permissionPattern_acceptsTwoToFourSegmentsAndRejectsMalformedCodes() {
        var valid = new UpdateRolePermissionsRequest(List.of(
                "customer:read", "ai:knowledge:config", "wecom:mapping:manage", "a:b:c:d"));
        assertThat(validator.validate(valid)).isEmpty();

        var invalid = new UpdateRolePermissionsRequest(List.of("UPPER:read", "single", "a:b:c:d:e"));
        assertThat(validator.validate(invalid)).hasSize(3);
    }

    @Test
    void nonAdminCannotModifyAdminRoleOrGrantOutOfScopePermissions() {
        SysRole adminRole = role(1L, "ADMIN");
        when(roleRepository.findRoleById(1L, 1L)).thenReturn(Optional.of(adminRole));
        when(roleRepository.findActiveRoleCodes(1L, 20L)).thenReturn(List.of("CUSTOM_USER_ADMIN"));

        assertThatThrownBy(() -> service.updateRolePermissions(1L, 20L, 1L,
                new UpdateRolePermissionsRequest(List.of("user:read", "user:write"))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        SysRole customRole = role(11L, "CUSTOM_EDITOR");
        when(roleRepository.findRoleById(1L, 11L)).thenReturn(Optional.of(customRole));
        when(roleRepository.findExistingPermissionCodes(1L, List.of("user:write", "refund:approve")))
                .thenReturn(List.of("user:write", "refund:approve"));
        when(roleRepository.findPermissionCodesByRoleId(1L, 11L)).thenReturn(List.of("user:read"));
        when(roleRepository.findActivePermissionCodes(1L, 20L)).thenReturn(List.of("user:read", "user:write"));

        assertThatThrownBy(() -> service.updateRolePermissions(1L, 20L, 11L,
                new UpdateRolePermissionsRequest(List.of("user:write", "refund:approve"))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(roleRepository, never()).replaceRolePermissions(anyLong(), anyLong(), any());
    }

    @Test
    void nonAdminCannotModifyRoleThatAlreadyHoldsPermissionsOutsideActorScope() {
        SysRole financeRole = role(6L, "FINANCE");
        when(roleRepository.findRoleById(1L, 6L)).thenReturn(Optional.of(financeRole));
        when(roleRepository.findActiveRoleCodes(1L, 20L)).thenReturn(List.of("CUSTOM_USER_ADMIN"));
        when(roleRepository.findExistingPermissionCodes(1L, List.of("user:read")))
                .thenReturn(List.of("user:read"));
        when(roleRepository.findPermissionCodesByRoleId(1L, 6L)).thenReturn(List.of("refund:approve"));
        when(roleRepository.findActivePermissionCodes(1L, 20L)).thenReturn(List.of("user:read", "user:write"));

        assertThatThrownBy(() -> service.updateRolePermissions(1L, 20L, 6L,
                new UpdateRolePermissionsRequest(List.of("user:read"))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void adminCannotRemoveUserWriteFromAdminRole() {
        SysRole adminRole = role(1L, "ADMIN");
        when(roleRepository.findRoleById(1L, 1L)).thenReturn(Optional.of(adminRole));
        when(roleRepository.findActiveRoleCodes(1L, 1L)).thenReturn(List.of("ADMIN"));

        assertThatThrownBy(() -> service.updateRolePermissions(1L, 1L, 1L,
                new UpdateRolePermissionsRequest(List.of("user:read"))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(roleRepository, never()).replaceRolePermissions(anyLong(), anyLong(), any());
    }

    private SysRole role(long id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setTenantId(1L);
        role.setRoleCode(code);
        role.setRoleName(code);
        role.setStatus("ACTIVE");
        role.setVersion(0L);
        return role;
    }
}
