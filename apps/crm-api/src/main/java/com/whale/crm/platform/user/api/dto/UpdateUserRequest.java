package com.whale.crm.platform.user.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 修改用户资料。version 乐观锁必填；password 为空表示不重置密码；username 不可改（稳定登录标识）。
 */
public record UpdateUserRequest(
        @NotBlank(message = "显示名不能为空") @Size(max = 64, message = "显示名最长 64 字符")
        String displayName,

        @Positive(message = "teamId 无效") Long teamId,

        @Size(min = 8, max = 64, message = "密码长度须为 8-64 位") String password,

        @NotNull(message = "version 必填") @Min(value = 0, message = "version 无效") Long version) {
}
