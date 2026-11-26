package com.whale.crm.customer.privacy.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 驳回隐私查看申请（驳回原因必填）。
 */
public record RejectPrivacyRequest(
        @NotBlank(message = "请填写驳回原因")
        @Size(max = 500, message = "驳回原因最长 500 字符")
        String reviewReason,

        @NotNull(message = "数据版本号不能为空")
        @Min(value = 0, message = "数据版本号无效")
        Long version) {
}
