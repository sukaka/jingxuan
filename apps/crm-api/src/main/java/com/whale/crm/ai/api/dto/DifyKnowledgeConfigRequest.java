package com.whale.crm.ai.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DifyKnowledgeConfigRequest(
        @NotNull @Pattern(regexp = "disabled|mock|dify") String provider,
        @Size(max = 1024) String baseUrl,
        @Size(max = 512) String apiKey,
        @Size(max = 128) String datasetId,
        @Size(max = 512) String datasetApiKey,
        @NotNull @Min(1) @Max(300) Integer timeoutSeconds,
        @NotNull Boolean enabled,
        Boolean syncEnabled,
        @NotNull @Min(0) Long version) {
}
