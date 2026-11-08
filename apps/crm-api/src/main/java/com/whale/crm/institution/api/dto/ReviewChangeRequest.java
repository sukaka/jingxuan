package com.whale.crm.institution.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReviewChangeRequest(@NotNull @Min(0) Long version,
                                  @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
                                  @Size(max = 1000) String comment) {
}
