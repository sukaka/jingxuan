package com.whale.crm.institution.api.dto;

import java.time.Instant;

public record InstitutionSettlementDefaultResponse(String defaultSettlementDay, long version,
                                                    boolean configured, Instant updatedAt) {
}
