package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 登记跟进。contactAt 缺省为当前时间；estimatedAmountFen 整数分。
 */
public record CreateFollowupRequest(
        @NotNull(message = "customerId 不能为空") Long customerId,
        @NotBlank(message = "沟通方式不能为空") @Size(max = 64) String channelCode,
        Instant contactAt,
        @Size(max = 2000) String summary,
        @Size(max = 192) String intentProject,
        @PositiveOrZero(message = "预计金额不能为负") Long estimatedAmountFen,
        Instant nextFollowupAt,
        @Size(max = 2000) String blockerNote) {
}
