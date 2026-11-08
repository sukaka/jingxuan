package com.whale.crm.exporting.domain;

import java.time.Instant;

public record ExportFilter(Long customerId, String status, String cityCode, String sourceCode,
                           String sourceSystem, String channelCode, Instant from, Instant to) {
    public static ExportFilter empty() {
        return new ExportFilter(null, null, null, null, null, null, null, null);
    }
}
