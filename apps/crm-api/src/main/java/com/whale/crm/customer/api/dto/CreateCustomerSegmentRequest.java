package com.whale.crm.customer.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCustomerSegmentRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull @Valid CustomerSegmentCriteria filters) {
}
