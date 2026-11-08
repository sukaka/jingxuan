package com.whale.crm.institution.api.dto;

import java.time.Instant;
import java.time.LocalDate;

public record QualificationResponse(Long id, Long institutionId, String qualificationType, String title,
                                    String licenseNoMasked, Long fileId, LocalDate validFrom, LocalDate validTo,
                                    String status, Long createdBy, Long reviewedBy, Instant reviewedAt,
                                    Instant createdAt, Long version) {
}
