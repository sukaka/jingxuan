package com.whale.crm.platform.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 个人账号自助修改密码请求。toString 脱敏，防止日志输出明文密码。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "当前密码不能为空") @Size(max = 128, message = "当前密码长度无效") String currentPassword,
        @NotBlank(message = "新密码不能为空") @Size(max = 128, message = "新密码长度无效") String newPassword,
        @NotBlank(message = "请再次输入新密码") @Size(max = 128, message = "确认密码长度无效") String confirmPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[REDACTED]";
    }
}
