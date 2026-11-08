package com.whale.crm.exporting.api.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ExportFilterRequest(
        @Positive Long customerId,
        @Size(max = 64) String status,
        @Size(max = 64) String cityCode,
        @Size(max = 64) String sourceCode,
        @Size(max = 48) String sourceSystem,
        @Size(max = 64) String channelCode,
        Instant from,
        Instant to) {
}
