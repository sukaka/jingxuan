package com.whale.crm.platform.security;

import com.whale.crm.platform.user.domain.InstitutionScopeRepository;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CurrentUserServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final InstitutionScopeRepository institutions = mock(InstitutionScopeRepository.class);
    private final CurrentUserService service = new CurrentUserService(users, roles, institutions);

    @Test
    void loadFirstActiveByRoleUsesRequestedTenant() {
        when(users.findStewardOptions(2L, List.of("ADMIN"), null, null))
                .thenReturn(List.of(new UserRepository.StewardOptionRow(22L, "租户2管理员", null, null)));
        SysUser admin = user(22L, 2L);
        when(users.findByIdForAuth(22L)).thenReturn(Optional.of(admin));
        when(roles.findActiveRoleCodes(2L, 22L)).thenReturn(List.of("ADMIN"));
        when(roles.findActivePermissionCodes(2L, 22L)).thenReturn(List.of("integration:youzan:manage"));

        CurrentUser result = service.loadFirstActiveByRole(2L, "ADMIN").orElseThrow();

        assertThat(result.userId()).isEqualTo(22L);
        assertThat(result.tenantId()).isEqualTo(2L);
        assertThat(result.permissions()).contains("integration:youzan:manage");
    }

    @Test
    void loadFirstActiveByRoleDoesNotFallbackToAnotherTenant() {
        when(users.findStewardOptions(2L, List.of("ADMIN"), null, null)).thenReturn(List.of());

        assertThat(service.loadFirstActiveByRole(2L, "ADMIN")).isEmpty();
        verify(users, never()).findByIdForAuth(1L);
    }

    private static SysUser user(long id, long tenantId) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setUsername("integration-admin");
        user.setDisplayName("集成管理员");
        user.setUserType("EMPLOYEE");
        user.setStatus("ACTIVE");
        return user;
    }
}
