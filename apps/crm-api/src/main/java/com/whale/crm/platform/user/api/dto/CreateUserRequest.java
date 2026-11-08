package com.whale.crm.platform.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建用户请求。org 由服务端按租户解析；tenantId 一律取自登录主体，不接受客户端传入。
 */
public record CreateUserRequest(
        @NotBlank(message = "登录名不能为空")
        @Pattern(regexp = "[A-Za-z0-9_.\\-]{3,32}", message = "登录名需为 3-32 位字母/数字/._-")
        String username,

        @NotBlank(message = "显示名不能为空") @Size(max = 64, message = "显示名最长 64 字符")
        String displayName,

        @NotBlank(message = "初始密码不能为空") @Size(min = 8, max = 64, message = "密码长度须为 8-64 位")
        String password,

        @NotBlank(message = "用户类型不能为空") @Pattern(regexp = "EMPLOYEE|INSTITUTION", message = "用户类型仅支持 EMPLOYEE/INSTITUTION")
        String userType,

        @Positive(message = "teamId 无效") Long teamId,

        List<@Positive(message = "roleId 无效") Long> roleIds) {
    public CreateUserRequest {
        roleIds = roleIds == null ? null : List.copyOf(roleIds);
    }
}
