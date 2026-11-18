package com.whale.crm.notification.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CreateWecomMappingRequest(
        @NotBlank @Size(max = 96) String corpId,
        @NotBlank @Size(max = 96) String wecomUserid,
        @NotNull @Min(1) Long staffUserId,
        @NotNull Boolean messageEnabled,
        @NotBlank @Size(max = 1000) String reason) {
}
