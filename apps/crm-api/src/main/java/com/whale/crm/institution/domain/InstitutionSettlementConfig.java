package com.whale.crm.institution.domain;

import java.time.Instant;

public record InstitutionSettlementConfig(Long id, long tenantId, String defaultSettlementDay,
                                           Long updatedBy, Instant createdAt, Instant updatedAt, long version) {
}
