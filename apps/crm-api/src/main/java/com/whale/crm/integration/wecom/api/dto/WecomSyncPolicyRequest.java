package com.whale.crm.integration.wecom.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record WecomSyncPolicyRequest(@NotNull Boolean enabled,
                                     @NotNull Boolean contactSyncEnabled,
                                     @NotNull Boolean archiveSyncEnabled,
                                     @NotNull @Min(60) @Max(86400) Integer intervalSeconds,
                                     Instant nextRunAt,
                                     Long version) {
}
