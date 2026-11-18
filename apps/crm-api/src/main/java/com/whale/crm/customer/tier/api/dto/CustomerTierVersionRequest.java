package com.whale.crm.customer.tier.api.dto;

import jakarta.validation.constraints.NotNull;

public record CustomerTierVersionRequest(@NotNull Long version) {
}
