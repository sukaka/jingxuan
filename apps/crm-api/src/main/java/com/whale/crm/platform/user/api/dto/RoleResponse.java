package com.whale.crm.platform.user.api.dto;

import java.util.List;

/**
 * 角色对外 DTO（含权限代码列表）。
 */
public record RoleResponse(
        Long id,
        String roleCode,
        String roleName,
        String status,
        List<String> permissions,
        Long version) {
    public RoleResponse {
        permissions = permissions == null ? null : List.copyOf(permissions);
    }
}
