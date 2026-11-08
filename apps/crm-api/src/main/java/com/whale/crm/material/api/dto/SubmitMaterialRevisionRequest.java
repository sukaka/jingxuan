package com.whale.crm.material.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SubmitMaterialRevisionRequest(@NotNull @Min(0) Long version) {
}
