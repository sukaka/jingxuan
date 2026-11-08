package com.whale.crm.trading.domain;

import java.time.Instant;
import java.time.LocalDate;

public record Entitlement(Long id, Long customerId, Long orderItemId, Long projectId, String projectName,
                          Integer grantedCount, Integer availableCount, Integer versionNo,
                          LocalDate validFrom, LocalDate validTo, String status, Instant createdAt, Long version) {
}
