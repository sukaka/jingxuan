package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 审批通过转交。version 为转交单乐观锁；客户 version 与 baseVersion 失配时 409 复核。
 */
public record ApproveTransferRequest(
        @NotNull(message = "version 不能为空") Long version) {
}
