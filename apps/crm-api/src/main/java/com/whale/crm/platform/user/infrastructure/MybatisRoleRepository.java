package com.whale.crm.platform.user.infrastructure;

import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysPermission;
import com.whale.crm.platform.user.domain.SysRole;
import com.whale.crm.platform.user.infrastructure.mapper.RoleMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisRoleRepository implements RoleRepository {

    private final RoleMapper roleMapper;

    public MybatisRoleRepository(RoleMapper roleMapper) {
        this.roleMapper = roleMapper;
    }

    @Override
    public List<SysRole> findAllRoles(long tenantId) {
        return roleMapper.selectRoles(tenantId);
    }

    @Override
    public Optional<SysRole> findRoleById(long tenantId, long roleId) {
        return Optional.ofNullable(roleMapper.selectRoleById(tenantId, roleId));
    }

    @Override
    public boolean existsRoleByCode(long tenantId, String roleCode) {
        return roleMapper.countRoleByCode(tenantId, roleCode) > 0;
    }

    @Override
    public void insertRole(SysRole role) {
        roleMapper.insertRole(role);
    }

    @Override
    public List<SysPermission> findAllPermissions(long tenantId) {
        return roleMapper.selectPermissions(tenantId);
    }

    @Override
    public List<String> findActiveRoleCodes(long tenantId, long userId) {
        return roleMapper.selectActiveRoleCodes(tenantId, userId);
    }

    @Override
    public List<String> findActivePermissionCodes(long tenantId, long userId) {
        return roleMapper.selectActivePermissionCodes(tenantId, userId);
    }

    @Override
    public List<UserRolePair> findActiveRolePairs(long tenantId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectActiveRolePairs(tenantId, userIds);
    }

    @Override
    public List<String> findPermissionCodesByRoleId(long tenantId, long roleId) {
        return roleMapper.selectPermissionCodesByRoleId(tenantId, roleId);
    }

    @Override
    public List<Long> findExistingRoleIds(long tenantId, Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectExistingRoleIds(tenantId, roleIds);
    }

    @Override
    public List<String> findExistingPermissionCodes(long tenantId, Collection<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectExistingPermissionCodes(tenantId, permissionCodes);
    }

    @Override
    public void replaceUserRoles(long tenantId, long userId, Collection<Long> roleIds) {
        roleMapper.deleteUserRoles(tenantId, userId);
        for (Long roleId : roleIds) {
            roleMapper.insertUserRole(tenantId, userId, roleId);
        }
    }

    @Override
    public void replaceRolePermissions(long tenantId, long roleId, Collection<String> permissionCodes) {
        roleMapper.deleteRolePermissions(tenantId, roleId);
        for (String code : permissionCodes) {
            roleMapper.insertRolePermissionByCode(tenantId, roleId, code);
        }
    }
}
