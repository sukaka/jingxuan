package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发起客户转交（PENDING）。同组 MANAGER_LEAD 可转本组客户（新管家也须在本组）；跨团队仅 ADMIN。
 * 批准后（approve）才在同一事务写 owner/team 与不可覆盖归属历史。
 */
public record CreateTransferRequest(
        @NotNull(message = "newOwnerId 不能为空") Long newOwnerId,
        @NotBlank(message = "转交原因不能为空") @Size(max = 500) String reason) {
}
