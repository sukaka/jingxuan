package com.whale.crm.customer.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 管家名录：客户归属/转交的目标必须是 ACTIVE 且持有 MANAGER 或 MANAGER_LEAD 角色的员工，
 * 财务/管理员等不得被当作管家。选项按角色范围收敛：ADMIN 全部、MANAGER_LEAD 本团队、MANAGER 仅本人。
 */
@Component
public class StewardDirectory {

    public static final List<String> STEWARD_ROLE_CODES = List.of("MANAGER", "MANAGER_LEAD");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public StewardDirectory(UserRepository userRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    public boolean isEligibleSteward(long tenantId, SysUser user) {
        if (!user.isActive() || !"EMPLOYEE".equals(user.getUserType())) {
            return false;
        }
        return roleRepository.findActiveRoleCodes(tenantId, user.getId()).stream()
                .anyMatch(STEWARD_ROLE_CODES::contains);
    }

    /**
     * 可选管家下拉（customer:read）：按当前用户角色收敛范围；其他角色空列表。
     */
    public List<UserRepository.StewardOptionRow> optionsFor(long tenantId, CurrentUser user) {
        if (user.roles().contains("ADMIN")) {
            return userRepository.findStewardOptions(tenantId, STEWARD_ROLE_CODES, null, null);
        }
        if (user.roles().contains("MANAGER_LEAD")) {
            return user.teamId() != null
                    ? userRepository.findStewardOptions(tenantId, STEWARD_ROLE_CODES, user.teamId(), null)
                    : userRepository.findStewardOptions(tenantId, STEWARD_ROLE_CODES, null, user.userId());
        }
        if (user.roles().contains("MANAGER")) {
            return userRepository.findStewardOptions(tenantId, STEWARD_ROLE_CODES, null, user.userId());
        }
        return List.of();
    }

    /**
     * 指定归属的数据范围校验：ADMIN 任意合格管家；MANAGER_LEAD 限本团队；MANAGER 限本人。
     */
    public boolean canAssignTo(CurrentUser user, SysUser owner) {
        if (user.roles().contains("ADMIN")) {
            return true;
        }
        if (user.roles().contains("MANAGER_LEAD")) {
            return owner.getId() == user.userId()
                    || (user.teamId() != null && user.teamId().equals(owner.getTeamId()));
        }
        if (user.roles().contains("MANAGER")) {
            return owner.getId() == user.userId();
        }
        return false;
    }
}
