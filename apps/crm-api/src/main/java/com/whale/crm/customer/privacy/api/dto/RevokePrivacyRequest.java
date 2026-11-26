package com.whale.crm.customer.privacy.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提前撤销已生效的隐私查看授权（撤销原因必填）。
 */
public record RevokePrivacyRequest(
        @NotBlank(message = "请填写撤销授权原因")
        @Size(max = 500, message = "撤销原因最长 500 字符")
        String revokeReason,

        @NotNull(message = "数据版本号不能为空")
        @Min(value = 0, message = "数据版本号无效")
        Long version) {
}
