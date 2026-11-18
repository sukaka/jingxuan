package com.whale.crm.integration.wecom.sidebar.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WecomJsSdkSignatureRequest(@NotBlank @Size(max = 2048) String url) {
}
