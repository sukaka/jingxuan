package com.whale.crm.campaign.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateChannelRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,48}") String channelNo,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 64) String platformCode,
        Long businessOwnerUserId,
        @Pattern(regexp = "ACTIVE|INACTIVE") String status,
        @Size(max = 4000) String remarks) {
}
