package com.whale.crm.platform.bootstrap;

import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysRole;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapAdminServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private AuditService auditService;
    private PasswordEncoder passwordEncoder;
    private BootstrapAdminService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        service = new BootstrapAdminService(userRepository, roleRepository, jdbcTemplate, auditService, passwordEncoder);
    }

    @Test
    void createsAdministratorWithScopesAndAuditedSafeSnapshot() {
        String password = "Synthetic-install-password-123";
        when(userRepository.existsByUsername(1L, "admin")).thenReturn(false);
        when(userRepository.findDefaultOrgId(1L)).thenReturn(Optional.of(1L));
        SysRole adminRole = new SysRole();
        adminRole.setId(7L);
        adminRole.setRoleCode("ADMIN");
        adminRole.setStatus("ACTIVE");
        when(roleRepository.findAllRoles(1L)).thenReturn(List.of(adminRole));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, SysUser.class).setId(41L);
            return null;
        }).when(userRepository).insert(any(SysUser.class));

        BootstrapAdminResult result = service.initialize("admin", "系统管理员", password);

        assertThat(result.created()).isTrue();
        assertThat(result.userId()).isEqualTo(41L);
        ArgumentCaptor<SysUser> user = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).insert(user.capture());
        assertThat(user.getValue().getUsername()).isEqualTo("admin");
        assertThat(user.getValue().getUserType()).isEqualTo("EMPLOYEE");
        assertThat(user.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(passwordEncoder.matches(password, user.getValue().getPasswordHash())).isTrue();
        assertThat(user.getValue().getPasswordHash()).doesNotContain(password);
        verify(roleRepository).replaceUserRoles(1L, 41L, List.of(7L));
        verify(jdbcTemplate).update(anyString(), eq(1L), eq(41L), eq(1L), eq(41L));
        ArgumentCaptor<Map<String, Object>> snapshot = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(1L), isNull(), eq(AuditService.ACTION_USER_CREATE), eq("USER"),
                eq("41"), isNull(), snapshot.capture(), eq("安装时创建首位管理员"));
        assertThat(snapshot.getValue()).containsEntry("username", "admin");
        assertThat(snapshot.getValue()).doesNotContainKeys("password", "passwordHash");
    }

    @Test
    void existingAdministratorIsNeverOverwritten() {
        when(userRepository.existsByUsername(1L, "admin")).thenReturn(true);

        BootstrapAdminResult result = service.initialize("admin", "系统管理员", "Unused-install-password-123");

        assertThat(result.created()).isFalse();
        verify(userRepository, never()).insert(any());
        verifyNoInteractions(roleRepository, jdbcTemplate, auditService);
    }

    @Test
    void rejectsWeakOrInvalidBootstrapCredentials() {
        assertThatThrownBy(() -> service.initialize("admin", "系统管理员", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12");
        assertThatThrownBy(() -> service.initialize("bad name", "系统管理员", "Synthetic-install-password-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("登录名");
        verifyNoInteractions(userRepository, roleRepository, jdbcTemplate, auditService);
    }
}
