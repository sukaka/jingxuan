package com.whale.crm.complaint.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateComplaintEmpathyScriptRequest(
        @NotBlank @Size(max = 64) String title,
        @NotBlank @Size(max = 2000) String script,
        @NotBlank @Size(max = 1000) String reason) {
}
