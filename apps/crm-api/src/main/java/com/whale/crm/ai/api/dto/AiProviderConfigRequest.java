package com.whale.crm.ai.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiProviderConfigRequest(
        @NotNull @Pattern(regexp = "disabled|mock|openai_compatible") String provider,
        @Size(max = 1024) String baseUrl,
        @Size(max = 512) String apiKey,
        @Size(max = 128) String model,
        @NotNull @Min(1) @Max(300) Integer timeoutSeconds,
        @NotNull Boolean enabled,
        @NotNull @Min(0) Long version) {
}
