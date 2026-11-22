package com.whale.crm.platform.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。可选 tenantCode（组织编码）；未传且用户名仅命中单租户时自动定位。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") @Size(max = 96) String username,
        @NotBlank(message = "密码不能为空") @Size(max = 128) String password,
        @NotBlank(message = "验证码标识不能为空") @Size(max = 64) String captchaId,
        @NotBlank(message = "验证码不能为空") @Size(max = 8) String captchaAnswer,
        @Size(max = 64, message = "组织编码最长 64 字符") String tenantCode) {

    public LoginRequest(String username, String password, String captchaId, String captchaAnswer) {
        this(username, password, captchaId, captchaAnswer, null);
    }
}
