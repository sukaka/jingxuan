package com.whale.crm.campaign.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReviewChannelFinancialChangeRequest(
        @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
        @NotNull @Min(0) Long version,
        @Size(max = 1000) String comment) {
}
