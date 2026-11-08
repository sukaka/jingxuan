package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 积分调整：只能追加流水（delta 正负），禁止直接 SET 余额；调整后余额不得为负。
 * 客户端可带 Idempotency-Key 防重复提交（同键同请求返回首次结果）。
 */
public record PointsAdjustmentRequest(
        @NotNull(message = "delta 不能为空") Integer delta,
        @NotBlank(message = "reasonCode 不能为空") @Size(max = 48) String reasonCode,
        @Size(max = 500) String reason) {
}
