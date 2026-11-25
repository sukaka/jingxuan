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
import java.util.Set;

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
        boolean actorIsAdmin = roleRepository.findActiveRoleCodes(tenantId, actorUserId).contains("ADMIN");
        if ("ADMIN".equals(role.getRoleCode()) && !actorIsAdmin) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅系统管理员可修改系统管理员角色权限");
        }
        List<String> codes = request.permissionCodes() == null
                ? List.of()
                : request.permissionCodes().stream().distinct().toList();
        if ("ADMIN".equals(role.getRoleCode()) && !codes.contains("user:write")) {
            throw new BusinessException(ErrorCode.CONFLICT, "不能移除系统管理员角色的用户管理权限");
        }
        List<String> existingCodes = roleRepository.findExistingPermissionCodes(tenantId, codes);
        if (existingCodes.size() != codes.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求参数校验失败",
                    List.of(new ApiResponse.ErrorDetail("permissionCodes", "包含不存在的权限代码")));
        }

        List<String> before = roleRepository.findPermissionCodesByRoleId(tenantId, roleId);
        if (!actorIsAdmin) {
            Set<String> actorPermissions = Set.copyOf(roleRepository.findActivePermissionCodes(tenantId, actorUserId));
            if (!actorPermissions.containsAll(before)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "不能修改包含超出当前用户权限范围的角色");
            }
            if (!actorPermissions.containsAll(codes)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "不能授予超出当前用户权限范围的权限");
            }
        }
        roleRepository.replaceRolePermissions(tenantId, roleId, codes);
        List<String> after = roleRepository.findPermissionCodesByRoleId(tenantId, roleId);

        auditService.record(tenantId, actorUserId, AuditService.ACTION_ROLE_PERMISSION_UPDATE, "ROLE",
                Long.toString(roleId), Map.of("roleCode", role.getRoleCode(), "permissions", before),
                Map.of("roleCode", role.getRoleCode(), "permissions", after), null);
        return new RoleResponse(role.getId(), role.getRoleCode(), role.getRoleName(), role.getStatus(), after,
                role.getVersion());
    }
}
