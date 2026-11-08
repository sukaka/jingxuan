package com.whale.crm.campaign.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateCampaignRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,64}") String campaignCode,
        @NotBlank @Size(max = 160) String campaignName,
        @NotBlank @Pattern(regexp = "REGULAR|FLASH|PROMOTION") String campaignType,
        Instant startsAt,
        Instant endsAt,
        @Pattern(regexp = "DRAFT|ACTIVE|INACTIVE") String status) {
}
