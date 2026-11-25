package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 人工登记合并候选（两客户必须在当前用户可见范围）。primary 为保留方。
 */
public record CreateMergeCaseRequest(
        @NotNull(message = "请选择主客户") Long primaryCustomerId,
        @NotNull(message = "请选择待合并客户") Long secondaryCustomerId,
        @Size(max = 500) String reason) {
}
