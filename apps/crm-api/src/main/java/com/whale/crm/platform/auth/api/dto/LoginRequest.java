package com.whale.crm.platform.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。不接受 tenantId：租户由登录名定位的用户行决定。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") @Size(max = 96) String username,
        @NotBlank(message = "密码不能为空") @Size(max = 128) String password,
        @NotBlank(message = "验证码标识不能为空") @Size(max = 64) String captchaId,
        @NotBlank(message = "验证码不能为空") @Size(max = 8) String captchaAnswer) {
}
