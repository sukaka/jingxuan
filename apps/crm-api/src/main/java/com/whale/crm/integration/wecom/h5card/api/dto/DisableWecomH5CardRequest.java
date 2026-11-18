package com.whale.crm.integration.wecom.h5card.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DisableWecomH5CardRequest(@NotNull @Min(0) Long version,
                                        @NotBlank @Size(max = 1000) String reason) {
}
