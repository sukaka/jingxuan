package com.whale.crm.platform.user.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.user.api.dto.CreateRoleRequest;
import com.whale.crm.platform.user.api.dto.PermissionResponse;
import com.whale.crm.platform.user.api.dto.RoleResponse;
import com.whale.crm.platform.user.api.dto.UpdateRolePermissionsRequest;
import com.whale.crm.platform.user.application.RoleApplicationService;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色与权限目录管理。角色/权限维护属用户管理域（user:read/user:write）。
 */
@RestController
@RequestMapping("/api/v1")
public class RoleController {

    private final RoleApplicationService roleService;

    public RoleController(RoleApplicationService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<List<RoleResponse>> listRoles(HttpServletRequest request) {
        return ApiResponse.ok(roleService.listRoles(TenantContext.currentTenantId()), requestId(request));
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('user:write')")
    public ResponseEntity<ApiResponse<RoleResponse>> createRole(@Valid @RequestBody CreateRoleRequest body,
                                                                HttpServletRequest request) {
        RoleResponse created = roleService.createRole(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PutMapping("/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('user:write')")
    public ApiResponse<RoleResponse> updateRolePermissions(@PathVariable long id,
                                                           @Valid @RequestBody UpdateRolePermissionsRequest body,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(
                roleService.updateRolePermissions(TenantContext.currentTenantId(),
                        SecuritySupport.requireCurrentUser().userId(), id, body),
                requestId(request));
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<List<PermissionResponse>> listPermissions(HttpServletRequest request) {
        return ApiResponse.ok(roleService.listPermissions(TenantContext.currentTenantId()), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
