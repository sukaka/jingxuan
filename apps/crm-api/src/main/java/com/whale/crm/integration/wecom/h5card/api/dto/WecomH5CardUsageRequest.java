package com.whale.crm.integration.wecom.h5card.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record WecomH5CardUsageRequest(@NotNull @Positive Long customerId,
                                      @NotBlank @Pattern(regexp = "COPIED|SDK_SUCCESS|SDK_FAILED|SDK_UNKNOWN") String sendStatus,
                                      @Size(max = 512) String clientResult) {
}
