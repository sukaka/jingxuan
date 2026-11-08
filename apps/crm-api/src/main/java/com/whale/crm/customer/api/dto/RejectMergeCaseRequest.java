package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 拒绝合并候选。
 */
public record RejectMergeCaseRequest(
        @NotNull(message = "version 不能为空") Long version,
        @NotBlank(message = "拒绝原因不能为空") @Size(max = 500) String reason) {
}
