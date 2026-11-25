package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateSegmentOperationBatchRequest(
        @Pattern(regexp = "FOLLOWUP", message = "当前仅支持回访操作批次") String operationType,
        @NotNull Long version) {
}
