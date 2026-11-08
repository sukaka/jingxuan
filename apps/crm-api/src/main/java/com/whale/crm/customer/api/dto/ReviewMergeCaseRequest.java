package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 合并单状态流转（review/confirm）。version 乐观锁。
 */
public record ReviewMergeCaseRequest(
        @NotNull(message = "version 不能为空") Long version,
        String reason) {
}
