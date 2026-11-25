package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 驳回转交申请。
 */
public record RejectTransferRequest(
        @NotNull(message = "数据版本号不能为空") Long version,
        @NotBlank(message = "驳回原因不能为空") @Size(max = 500) String reason) {
}
