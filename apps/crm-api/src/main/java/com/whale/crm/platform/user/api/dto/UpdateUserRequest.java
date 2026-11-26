package com.whale.crm.platform.user.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 修改用户资料。version 乐观锁必填；password 为空表示不重置密码；username 不可改（稳定登录标识）。
 */
public record UpdateUserRequest(
        @NotBlank(message = "显示名不能为空") @Size(max = 64, message = "显示名最长 64 字符")
        String displayName,

        @Positive(message = "所选团队无效") Long teamId,

        @Size(min = 8, max = 64, message = "密码长度须为 8-64 位")
        @Pattern(regexp = "(?s).*\\S.*", message = "密码不能为空白字符")
        String password,

        @NotNull(message = "数据版本号必填") @Min(value = 0, message = "数据版本号无效") Long version,

        @Positive(message = "所选直属上级无效") Long supervisorUserId) {

    public UpdateUserRequest(String displayName, Long teamId, String password, Long version) {
        this(displayName, teamId, password, version, null);
    }
}
