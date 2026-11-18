package com.whale.crm.followup.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateMonthlyBatchRequest(
        @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "orderMonth 格式必须为 YYYY-MM")
        String orderMonth) {
}
