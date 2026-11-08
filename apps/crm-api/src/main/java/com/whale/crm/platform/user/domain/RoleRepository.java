package com.whale.crm.platform.user.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 角色/权限/用户角色映射存取，全部显式 tenantId 过滤。
 */
public interface RoleRepository {

    List<SysRole> findAllRoles(long tenantId);

    Optional<SysRole> findRoleById(long tenantId, long roleId);

    boolean existsRoleByCode(long tenantId, String roleCode);

    void insertRole(SysRole role);

    List<SysPermission> findAllPermissions(long tenantId);

    /**
     * 用户当前启用角色代码（仅 status=ACTIVE 角色参与授权）。
     */
    List<String> findActiveRoleCodes(long tenantId, long userId);

    /**
     * 用户经启用角色聚合后的权限代码（去重）。
     */
    List<String> findActivePermissionCodes(long tenantId, long userId);

    /**
     * 列表页批量取角色：返回 (userId, roleCode) 平铺行，由应用层分组。
     */
    List<UserRolePair> findActiveRolePairs(long tenantId, Collection<Long> userIds);

    List<String> findPermissionCodesByRoleId(long tenantId, long roleId);

    /**
     * 校验角色 ID 集合在本租户内存在，返回实际存在的 ID。
     */
    List<Long> findExistingRoleIds(long tenantId, Collection<Long> roleIds);

    List<String> findExistingPermissionCodes(long tenantId, Collection<String> permissionCodes);

    void replaceUserRoles(long tenantId, long userId, Collection<Long> roleIds);

    void replaceRolePermissions(long tenantId, long roleId, Collection<String> permissionCodes);

    /**
     * (userId, roleCode) 平铺行（MyBatis 映射用可变类）。
     */
    class UserRolePair {

        private Long userId;
        private String roleCode;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getRoleCode() {
            return roleCode;
        }

        public void setRoleCode(String roleCode) {
            this.roleCode = roleCode;
        }
    }
}
