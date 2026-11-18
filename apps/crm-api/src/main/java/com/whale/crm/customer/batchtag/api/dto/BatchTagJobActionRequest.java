package com.whale.crm.customer.batchtag.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record BatchTagJobActionRequest(@NotNull @Min(0) Long version) {
}
