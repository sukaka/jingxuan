package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 人工登记合并候选（两客户必须在当前用户可见范围）。primary 为保留方。
 */
public record CreateMergeCaseRequest(
        @NotNull(message = "primaryCustomerId 不能为空") Long primaryCustomerId,
        @NotNull(message = "secondaryCustomerId 不能为空") Long secondaryCustomerId,
        @Size(max = 500) String reason) {
}
