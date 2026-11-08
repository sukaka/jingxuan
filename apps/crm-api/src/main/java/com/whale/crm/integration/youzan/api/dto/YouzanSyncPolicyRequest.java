package com.whale.crm.integration.youzan.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record YouzanSyncPolicyRequest(@NotNull Instant initialStartTime,
                                      @NotNull @Min(60) @Max(86400) Integer intervalSeconds,
                                      @NotNull @Min(0) @Max(3600) Integer overlapSeconds,
                                      @NotNull @Min(1) @Max(100) Integer pageSize,
                                      @NotNull Boolean enabled,
                                      Instant nextRunAt,
                                      Long version) {
}
