package com.whale.crm.complaint.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SourceDetectionRequest(@NotNull @PositiveOrZero Long version) {
}
