package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 修改跟进（创建者或同组主管）。未传字段保持不变；version 乐观锁。
 */
public record UpdateFollowupRequest(
        @Size(max = 64) String channelCode,
        Instant contactAt,
        @Size(max = 2000) String summary,
        @Size(max = 192) String intentProject,
        @PositiveOrZero(message = "预计金额不能为负") Long estimatedAmountFen,
        Instant nextFollowupAt,
        @Size(max = 2000) String blockerNote,
        @NotNull(message = "version 不能为空") Long version) {
}
