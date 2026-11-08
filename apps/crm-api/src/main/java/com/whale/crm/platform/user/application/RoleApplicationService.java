package com.whale.crm.platform.user.application;

import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.user.api.dto.CreateRoleRequest;
import com.whale.crm.platform.user.api.dto.PermissionResponse;
import com.whale.crm.platform.user.api.dto.RoleResponse;
import com.whale.crm.platform.user.api.dto.UpdateRolePermissionsRequest;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysRole;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 角色与权限目录管理。权限集合覆盖式替换；因认证层每请求实时解析，变更对下一请求立即生效。
 */
@Service
public class RoleApplicationService {

    private final RoleRepository roleRepository;
    private final AuditService auditService;

    public RoleApplicationService(RoleRepository roleRepository, AuditService auditService) {
        this.roleRepository = roleRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles(long tenantId) {
        return roleRepository.findAllRoles(tenantId).stream()
                .map(role -> new RoleResponse(role.getId(), role.getRoleCode(), role.getRoleName(), role.getStatus(),
                        roleRepository.findPermissionCodesByRoleId(tenantId, role.getId()), role.getVersion()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> listPermissions(long tenantId) {
        return roleRepository.findAllPermissions(tenantId).stream()
                .map(permission -> new PermissionResponse(permission.getId(), permission.getPermissionCode(),
                        permission.getDescription()))
                .toList();
    }

    @Transactional
    public RoleResponse createRole(long tenantId, long actorUserId, CreateRoleRequest request) {
        if (roleRepository.existsRoleByCode(tenantId, request.roleCode())) {
            throw new BusinessException(ErrorCode.CONFLICT, "角色编码已存在");
        }
        SysRole role = new SysRole();
        role.setTenantId(tenantId);
        role.setRoleCode(request.roleCode());
        role.setRoleName(request.roleName());
        role.setStatus("ACTIVE");
        roleRepository.insertRole(role);

        SysRole persisted = roleRepository.findRoleById(tenantId, role.getId()).orElseThrow();
        auditService.record(tenantId, actorUserId, AuditService.ACTION_ROLE_CREATE, "ROLE",
                Long.toString(role.getId()), null,
                Map.of("roleCode", persisted.getRoleCode(), "roleName", persisted.getRoleName(),
                        "status", persisted.getStatus()),
                null);
        return new RoleResponse(persisted.getId(), persisted.getRoleCode(), persisted.getRoleName(),
                persisted.getStatus(), List.of(), persisted.getVersion());
    }

    @Transactional
    public RoleResponse updateRolePermissions(long tenantId, long actorUserId, long roleId,
                                              UpdateRolePermissionsRequest request) {
        SysRole role = roleRepository.findRoleById(tenantId, roleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在或不可见"));
        List<String> codes = request.permissionCodes() == null
                ? List.of()
                : request.permissionCodes().stream().distinct().toList();
        List<String> existingCodes = roleRepository.findExistingPermissionCodes(tenantId, codes);
        if (existingCodes.size() != codes.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求参数校验失败",
                    List.of(new ApiResponse.ErrorDetail("permissionCodes", "包含不存在的权限代码")));
        }

        List<String> before = roleRepository.findPermissionCodesByRoleId(tenantId, roleId);
        roleRepository.replaceRolePermissions(tenantId, roleId, codes);
        List<String> after = roleRepository.findPermissionCodesByRoleId(tenantId, roleId);

        auditService.record(tenantId, actorUserId, AuditService.ACTION_ROLE_PERMISSION_UPDATE, "ROLE",
                Long.toString(roleId), Map.of("roleCode", role.getRoleCode(), "permissions", before),
                Map.of("roleCode", role.getRoleCode(), "permissions", after), null);
        return new RoleResponse(role.getId(), role.getRoleCode(), role.getRoleName(), role.getStatus(), after,
                role.getVersion());
    }
}
