package com.whale.crm.complaint.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateComplaintKeywordRequest(
        @NotBlank @Size(max = 128) String keyword) {
}
