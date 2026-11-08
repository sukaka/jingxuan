package com.whale.crm.refund.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReviewRefundRequest(
        @NotNull Long version,
        @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
        @Min(1) Long approvedFen,
        @NotBlank @Size(max = 1000) String reason) {
}
