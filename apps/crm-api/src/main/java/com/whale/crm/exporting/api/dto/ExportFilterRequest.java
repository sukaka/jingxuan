package com.whale.crm.exporting.api.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ExportFilterRequest(
        @Positive Long customerId,
        @Size(max = 64) String status,
        @Size(max = 64) String cityCode,
        @Size(max = 32) String provinceCode,
        @Size(max = 32) String regionCityCode,
        @Size(max = 32) String districtCode,
        @Size(max = 64) String sourceCode,
        @Size(max = 48) String sourceSystem,
        @Size(max = 64) String channelCode,
        Instant from,
        Instant to) {
    public ExportFilterRequest(Long customerId, String status, String cityCode, String sourceCode,
                               String sourceSystem, String channelCode, Instant from, Instant to) {
        this(customerId, status, cityCode, null, null, null, sourceCode, sourceSystem, channelCode, from, to);
    }
}
