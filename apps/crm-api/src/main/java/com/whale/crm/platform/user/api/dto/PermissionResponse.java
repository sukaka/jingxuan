package com.whale.crm.platform.user.api.dto;

/**
 * 权限目录对外 DTO。
 */
public record PermissionResponse(Long id, String permissionCode, String description) {
}
