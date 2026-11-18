package com.whale.crm.followup.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record MonthlyBatchActionRequest(@NotNull @PositiveOrZero Long version) {
}
