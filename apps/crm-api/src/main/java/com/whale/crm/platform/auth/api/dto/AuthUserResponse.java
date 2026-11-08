package com.whale.crm.platform.auth.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 登录/会话主体信息：login 与 me 共用同一结构。roles/permissions 实时来自 DB。
 */
public record AuthUserResponse(
        Long userId,
        String username,
        String displayName,
        String userType,
        List<String> roles,
        List<String> permissions,
        Map<Long, String> institutionRoles) {
    public AuthUserResponse {
        roles = roles == null ? null : List.copyOf(roles);
        permissions = permissions == null ? null : List.copyOf(permissions);
        institutionRoles = institutionRoles == null ? null : Map.copyOf(institutionRoles);
    }
}
