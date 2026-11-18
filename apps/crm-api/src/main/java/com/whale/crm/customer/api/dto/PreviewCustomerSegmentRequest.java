package com.whale.crm.customer.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PreviewCustomerSegmentRequest(
        @NotNull @Valid CustomerSegmentCriteria filters,
        @Min(1) Integer page,
        @Min(1) @Max(100) Integer size) {
    public PreviewCustomerSegmentRequest {
        page = page == null ? Integer.valueOf(1) : page;
        size = size == null ? Integer.valueOf(20) : size;
    }
}
