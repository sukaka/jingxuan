package com.whale.crm.platform.bootstrap;

import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BootstrapAdminServiceIT extends AbstractMysqlIT {
    @Autowired
    private BootstrapAdminService bootstrapAdminService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @Transactional
    void createsUsableAdministratorAndNeverResetsPasswordOnRetry() throws Exception {
        String username = "install_it_admin";
        String password = "Synthetic-install-IT-password-123";
        BootstrapAdminResult created = bootstrapAdminService.initialize(username, "安装测试管理员", password);

        assertThat(created.created()).isTrue();
        SysUser user = userRepository.findByUsernameForAuth(username).orElseThrow();
        assertThat(passwordEncoder.matches(password, user.getPasswordHash())).isTrue();
        assertThat(roleRepository.findActiveRoleCodes(1L, user.getId())).containsExactly("ADMIN");
        assertThat(roleRepository.findActivePermissionCodes(1L, user.getId()))
                .contains("customer:read", "user:write", "ai:knowledge:publish");

        BootstrapAdminResult repeated = bootstrapAdminService.initialize(username, "不应覆盖", "Another-install-password-123");
        assertThat(repeated.created()).isFalse();
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"));
    }
}
