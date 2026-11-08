package com.whale.crm.platform.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建角色请求。roleCode 稳定大写标识，创建后不可改。
 */
public record CreateRoleRequest(
        @NotBlank(message = "角色编码不能为空")
        @Pattern(regexp = "[A-Z0-9_]{2,32}", message = "角色编码需为 2-32 位大写字母/数字/下划线")
        String roleCode,

        @NotBlank(message = "角色名称不能为空") @Size(max = 64, message = "角色名称最长 64 字符")
        String roleName) {
}
