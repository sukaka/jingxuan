package com.whale.crm.customer.batchtag.api.dto;

import com.whale.crm.customer.api.dto.CustomerSegmentCriteria;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record BatchTagSourceRequest(
        @NotBlank @Pattern(regexp = "FILTER|SEGMENT|OPERATION_BATCH") String type,
        @Positive Long id,
        @Valid CustomerSegmentCriteria filters,
        @Size(max = 128) String batchNo) {
}
