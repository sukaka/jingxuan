package com.whale.crm.file.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UploadCompleteRequest(@NotNull @Min(1) Long fileId,
                                    @NotBlank @Size(max = 128) String etag) {
}
