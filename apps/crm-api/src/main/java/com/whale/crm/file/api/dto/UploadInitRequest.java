package com.whale.crm.file.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UploadInitRequest(
        @NotBlank @Size(max = 255) String originalName,
        @NotBlank @Pattern(regexp = "application/pdf|image/jpeg|image/png|text/plain|text/markdown|text/x-markdown") String mimeType,
        @NotNull @Min(1) Long sizeBytes,
        @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{64}") String sha256,
        @NotBlank @Pattern(regexp = "CONTRACT|QUALIFICATION|CHANGE_EVIDENCE|PAYMENT|REDEMPTION|MATERIAL") String classification,
        @Min(1) Long institutionId) {
}
