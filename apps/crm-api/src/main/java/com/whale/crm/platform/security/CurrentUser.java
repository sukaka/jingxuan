package com.whale.crm.platform.security;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

/**
 * 当前登录主体。每个请求由 JwtAuthenticationFilter 实时从 MySQL 解析（角色/权限/机构映射不缓存），
 * 因此权限变更、角色摘除、账号停用对下一个请求立即生效。
 *
 * @param institutionIds 机构账号可见的机构集合（user_type=INSTITUTION 时来自 crm_institution_user）
 */
public record CurrentUser(
        long userId,
        long tenantId,
        String username,
        String displayName,
        String userType,
        Long teamId,
        Set<String> roles,
        Set<String> permissions,
        Set<Long> institutionIds) implements Serializable {
    public CurrentUser {
        roles = roles == null ? null : Set.copyOf(roles);
        permissions = permissions == null ? null : Set.copyOf(permissions);
        institutionIds = institutionIds == null ? null : Set.copyOf(institutionIds);
    }

    public boolean isInstitutionUser() {
        return "INSTITUTION".equals(userType);
    }

    public List<String> sortedRoles() {
        return roles.stream().sorted().toList();
    }

    public List<String> sortedPermissions() {
        return permissions.stream().sorted().toList();
    }
}
