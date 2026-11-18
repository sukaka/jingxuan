package com.whale.crm.complaint.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ResolveComplaintRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 2000) String resolution) {
}
