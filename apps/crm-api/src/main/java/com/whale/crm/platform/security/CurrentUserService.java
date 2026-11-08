package com.whale.crm.platform.security;

import com.whale.crm.platform.user.domain.InstitutionScopeRepository;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 登录主体解析：每次调用实时读 MySQL（角色/权限/机构映射不做缓存），
 * 因此权限变更、角色摘除、账号停用对下一个请求立即生效。
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final InstitutionScopeRepository institutionScopeRepository;

    public CurrentUserService(UserRepository userRepository,
                              RoleRepository roleRepository,
                              InstitutionScopeRepository institutionScopeRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.institutionScopeRepository = institutionScopeRepository;
    }

    /**
     * 按用户 ID 组装主体；用户不存在或已停用时返回空（按未认证处理）。
     */
    @Transactional(readOnly = true)
    public Optional<CurrentUser> loadById(long userId) {
        return userRepository.findByIdForAuth(userId)
                .filter(SysUser::isActive)
                .map(this::toCurrentUser);
    }

    @Transactional(readOnly = true)
    public Optional<CurrentUser> loadFirstActiveByRole(long tenantId, String roleCode) {
        return userRepository.findStewardOptions(tenantId, java.util.List.of(roleCode), null, null)
                .stream().findFirst().flatMap(option -> loadById(option.id()));
    }

    public CurrentUser toCurrentUser(SysUser user) {
        long tenantId = user.getTenantId();
        Set<String> roles = new LinkedHashSet<>(roleRepository.findActiveRoleCodes(tenantId, user.getId()));
        Set<String> permissions =
                new LinkedHashSet<>(roleRepository.findActivePermissionCodes(tenantId, user.getId()));
        Set<Long> institutionIds = user.isActive() && "INSTITUTION".equals(user.getUserType())
                ? new LinkedHashSet<>(institutionScopeRepository.findActiveInstitutionIds(tenantId, user.getId()))
                : Set.of();
        return new CurrentUser(user.getId(), tenantId, user.getUsername(), user.getDisplayName(),
                user.getUserType(), user.getTeamId(), roles, permissions, institutionIds);
    }
}
