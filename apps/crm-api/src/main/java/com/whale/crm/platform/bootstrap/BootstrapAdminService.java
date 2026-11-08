package com.whale.crm.platform.bootstrap;

import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysRole;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class BootstrapAdminService {
    private static final long TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final String USERNAME_PATTERN = "[A-Za-z0-9_.\\-]{3,32}";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAdminService(UserRepository userRepository, RoleRepository roleRepository,
                                 JdbcTemplate jdbcTemplate, AuditService auditService,
                                 PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public BootstrapAdminResult initialize(String username, String displayName, String password) {
        if (username == null || !username.matches(USERNAME_PATTERN)) {
            throw new IllegalArgumentException("登录名需为 3-32 位字母/数字/._-");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 64) {
            throw new IllegalArgumentException("显示名不能为空且最长 64 字符");
        }
        if (password == null || password.length() < 12 || password.length() > 64) {
            throw new IllegalArgumentException("初始密码长度须为 12-64 位");
        }
        if (userRepository.existsByUsername(TENANT_ID, username)) {
            return new BootstrapAdminResult(0L, false);
        }
        Long orgId = userRepository.findDefaultOrgId(TENANT_ID)
                .orElseThrow(() -> new IllegalStateException("初始化组织不存在"));
        SysRole adminRole = roleRepository.findAllRoles(TENANT_ID).stream()
                .filter(role -> "ADMIN".equals(role.getRoleCode()) && "ACTIVE".equals(role.getStatus()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("初始化管理员角色不存在"));

        SysUser user = new SysUser();
        user.setTenantId(TENANT_ID);
        user.setOrgId(orgId);
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setUserType("EMPLOYEE");
        user.setStatus(SysUser.STATUS_ACTIVE);
        userRepository.insert(user);
        roleRepository.replaceUserRoles(TENANT_ID, user.getId(), List.of(adminRole.getId()));
        jdbcTemplate.update("""
                INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id)
                SELECT ?, ?, resources.resource, 'ALL', NULL
                FROM (SELECT 'customer' AS resource UNION ALL SELECT 'institution') resources
                WHERE NOT EXISTS (
                    SELECT 1 FROM sys_data_scope existing
                    WHERE existing.tenant_id=? AND existing.user_id=? AND existing.resource=resources.resource
                )
                """, TENANT_ID, user.getId(), TENANT_ID, user.getId());
        auditService.record(TENANT_ID, null, AuditService.ACTION_USER_CREATE, "USER",
                Long.toString(user.getId()), null,
                Map.of("username", username, "displayName", displayName,
                        "roles", List.of("ADMIN"), "source", "installer"), "安装时创建首位管理员");
        return new BootstrapAdminResult(user.getId(), true);
    }
}
