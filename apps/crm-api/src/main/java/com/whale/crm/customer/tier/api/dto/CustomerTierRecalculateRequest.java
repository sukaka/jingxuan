package com.whale.crm.customer.tier.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CustomerTierRecalculateRequest(@NotNull Long version, Instant asOf) {
}
