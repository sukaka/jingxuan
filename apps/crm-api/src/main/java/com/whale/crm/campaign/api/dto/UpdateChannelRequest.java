package com.whale.crm.campaign.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateChannelRequest(
        @Pattern(regexp = "[A-Za-z0-9_-]{2,48}") String channelNo,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 64) String platformCode,
        Long businessOwnerUserId,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE|ARCHIVED") String status,
        @Size(max = 4000) String remarks,
        @NotNull @Min(0) Long version) {
}
