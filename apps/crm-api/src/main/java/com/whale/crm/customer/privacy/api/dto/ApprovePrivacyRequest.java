package com.whale.crm.customer.privacy.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 上级主管 / 管理员批准客户隐私查看申请。
 * approvedFields 可空（默认批准全部申请字段，若指定须为申请字段子集）；
 * durationMinutes 可空（默认沿用申请时长或 30 分钟，可选 15/30/120/1440）。
 */
public record ApprovePrivacyRequest(
        @Size(max = 4, message = "批准字段数量超限")
        List<String> approvedFields,

        Integer durationMinutes,

        @Size(max = 500, message = "审批意见最长 500 字符")
        String reviewReason,

        @NotNull(message = "数据版本号不能为空")
        @Min(value = 0, message = "数据版本号无效")
        Long version) {
    public ApprovePrivacyRequest {
        approvedFields = approvedFields == null ? null : List.copyOf(approvedFields);
    }
}
