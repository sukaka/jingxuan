package com.whale.crm.customer.privacy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 显式点击按钮揭盲客户隐私明文请求。
 * ADMIN 走 mode=ADMIN_DIRECT 时 reason 必填；非 ADMIN 必须已存在有效审批授权（可指定 requestId）。
 */
public record RevealCustomerPrivacyRequest(
        @NotEmpty(message = "请至少选择一项需要查看的隐私字段")
        @Size(max = 4, message = "单次最多查看 4 项隐私字段")
        List<@NotBlank(message = "字段类型不能为空") String> fields,

        @Size(max = 500, message = "查看原因最长 500 字符")
        String reason,

        @Positive(message = "授权单 ID 无效")
        Long requestId,

        @Size(max = 32)
        String mode) {
    public RevealCustomerPrivacyRequest {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
