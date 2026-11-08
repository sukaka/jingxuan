package com.whale.crm.campaign.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateCampaignBatchRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String batchCode,
        @NotBlank @Size(max = 96) String batchName,
        Instant startsAt,
        Instant endsAt,
        @Pattern(regexp = "DRAFT|ACTIVE") String status) {
}
