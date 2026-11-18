package com.whale.crm.integration.wecom.h5card.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateWecomH5CardRequest(@NotNull @Positive Long customerId, @NotNull @Positive Long projectId,
                                       @NotBlank @Size(max = 192) String externalUserid,
                                       @NotBlank @Size(max = 160) String title,
                                       @NotBlank @Size(max = 500) String summary,
                                       @NotBlank @Size(max = 4000) String content) {
}
