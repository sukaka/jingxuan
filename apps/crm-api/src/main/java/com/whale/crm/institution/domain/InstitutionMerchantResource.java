package com.whale.crm.institution.domain;

import java.time.Instant;

public record InstitutionMerchantResource(Long id, String resourceNo, Long institutionId, String resourceType,
                                          String title, String subtitle, String description, Long fileId,
                                          String status, Long createdBy, Long updatedBy,
                                          Instant createdAt, Instant updatedAt, Long version) {
}
