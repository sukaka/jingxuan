package com.whale.crm.customer.privacy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交客户隐私查看申请。
 */
public record CreatePrivacyRequest(
        @NotEmpty(message = "请至少选择一项需要查看的隐私字段")
        @Size(max = 4, message = "单次最多申请 4 项隐私字段")
        List<@NotBlank(message = "字段类型不能为空") String> fields,

        Integer durationMinutes,

        @NotBlank(message = "请填写申请查看原因")
        @Size(max = 500, message = "申请原因最长 500 字符")
        String reason) {
    public CreatePrivacyRequest {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
