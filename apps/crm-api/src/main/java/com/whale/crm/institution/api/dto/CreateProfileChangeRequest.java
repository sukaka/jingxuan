package com.whale.crm.institution.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProfileChangeRequest(@NotNull Long institutionId,
                                         @NotBlank @Size(max = 64) String fieldCode,
                                         @NotNull JsonNode newValue,
                                         @NotNull @Min(0) Long baseVersion,
                                         @NotBlank @Size(max = 1000) String reason,
                                         Long evidenceFileId) {
}
