package com.whale.crm.customer.privacy.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 申请人撤回待审批的隐私查看申请。
 */
public record CancelPrivacyRequest(
        @NotNull(message = "数据版本号不能为空")
        @Min(value = 0, message = "数据版本号无效")
        Long version) {
}
