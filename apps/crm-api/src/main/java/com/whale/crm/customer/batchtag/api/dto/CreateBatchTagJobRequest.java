package com.whale.crm.customer.batchtag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateBatchTagJobRequest(@NotBlank @Size(max = 64) String previewId) {
}
