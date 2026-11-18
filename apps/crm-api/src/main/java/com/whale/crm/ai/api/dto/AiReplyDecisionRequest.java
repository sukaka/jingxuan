package com.whale.crm.ai.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiReplyDecisionRequest(@NotBlank @Pattern(regexp = "ACCEPT|REJECT") String decision,
                                     @NotNull @Min(0) Long version,
                                     @Size(max = 4000) String finalContent,
                                     @NotBlank @Size(max = 500) String reason) {
}
