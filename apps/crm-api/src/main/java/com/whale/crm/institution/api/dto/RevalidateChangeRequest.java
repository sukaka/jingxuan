package com.whale.crm.institution.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RevalidateChangeRequest(@NotNull @Min(0) Long version,
                                      @NotNull @Min(0) Long baseVersion,
                                      @NotBlank @Size(max = 1000) String reason) {
}
