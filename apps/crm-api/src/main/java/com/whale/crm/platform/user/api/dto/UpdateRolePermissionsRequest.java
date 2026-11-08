package com.whale.crm.platform.user.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 覆盖式设置角色权限。权限代码必须在 sys_permission 目录内。
 */
public record UpdateRolePermissionsRequest(
        @NotNull(message = "permissionCodes 必填")
        List<@Pattern(regexp = "[a-z]+:[a-z_]+", message = "权限代码格式无效") String> permissionCodes) {
    public UpdateRolePermissionsRequest {
        permissionCodes = permissionCodes == null ? null : List.copyOf(permissionCodes);
    }
}
