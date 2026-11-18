package com.whale.crm.ai.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record GenerateAiReplyRequest(@NotNull @Positive Long customerId, @NotNull @Positive Long templateId,
                                     @Size(max = 500) String instruction) {
}
