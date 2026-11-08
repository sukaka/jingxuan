package com.whale.crm.settlement.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateSettlementBatchRequest(
        @NotNull @Positive Long institutionId,
        @NotNull @Pattern(regexp = "MONTHLY|PER_REDEMPTION") String settlementType,
        @NotNull @Size(min = 1, max = 64) String periodKey,
        @NotEmpty @Size(max = 1000) List<@NotNull @Positive Long> redemptionIds) {
    public CreateSettlementBatchRequest {
        redemptionIds = redemptionIds == null ? null : List.copyOf(redemptionIds);
    }
}
