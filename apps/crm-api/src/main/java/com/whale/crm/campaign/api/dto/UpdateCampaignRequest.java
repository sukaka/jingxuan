package com.whale.crm.campaign.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record UpdateCampaignRequest(
        @Pattern(regexp = "[A-Za-z0-9_-]{2,64}") String campaignCode,
        @NotBlank @Size(max = 160) String campaignName,
        @NotBlank @Pattern(regexp = "REGULAR|FLASH|PROMOTION") String campaignType,
        Instant startsAt,
        Instant endsAt,
        @NotBlank @Pattern(regexp = "DRAFT|ACTIVE|INACTIVE|ARCHIVED") String status,
        @NotNull @Min(0) Long version) {
}
