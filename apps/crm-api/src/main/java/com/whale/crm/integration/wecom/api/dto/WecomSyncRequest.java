package com.whale.crm.integration.wecom.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WecomSyncRequest(@NotBlank @Size(max = 96) String corpId,
                               @Min(0) Long cursor,
                               @Min(1) @Max(100) Integer limit) {
}
