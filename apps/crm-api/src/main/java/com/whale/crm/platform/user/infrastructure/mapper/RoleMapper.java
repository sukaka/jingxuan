package com.whale.crm.platform.user.infrastructure.mapper;

import com.whale.crm.platform.user.domain.RoleRepository.UserRolePair;
import com.whale.crm.platform.user.domain.SysPermission;
import com.whale.crm.platform.user.domain.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface RoleMapper {

    List<SysRole> selectRoles(@Param("tenantId") long tenantId);

    SysRole selectRoleById(@Param("tenantId") long tenantId, @Param("roleId") long roleId);

    int countRoleByCode(@Param("tenantId") long tenantId, @Param("roleCode") String roleCode);

    int insertRole(SysRole role);

    List<SysPermission> selectPermissions(@Param("tenantId") long tenantId);

    List<String> selectActiveRoleCodes(@Param("tenantId") long tenantId, @Param("userId") long userId);

    List<String> selectActivePermissionCodes(@Param("tenantId") long tenantId, @Param("userId") long userId);

    List<UserRolePair> selectActiveRolePairs(@Param("tenantId") long tenantId,
                                             @Param("userIds") Collection<Long> userIds);

    List<String> selectPermissionCodesByRoleId(@Param("tenantId") long tenantId, @Param("roleId") long roleId);

    List<Long> selectExistingRoleIds(@Param("tenantId") long tenantId, @Param("roleIds") Collection<Long> roleIds);

    List<String> selectExistingPermissionCodes(@Param("tenantId") long tenantId,
                                               @Param("permissionCodes") Collection<String> permissionCodes);

    int deleteUserRoles(@Param("tenantId") long tenantId, @Param("userId") long userId);

    int insertUserRole(@Param("tenantId") long tenantId, @Param("userId") long userId, @Param("roleId") long roleId);

    int deleteRolePermissions(@Param("tenantId") long tenantId, @Param("roleId") long roleId);

    int insertRolePermissionByCode(@Param("tenantId") long tenantId, @Param("roleId") long roleId,
                                   @Param("permissionCode") String permissionCode);
}
