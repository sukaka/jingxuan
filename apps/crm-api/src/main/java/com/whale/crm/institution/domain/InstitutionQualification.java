package com.whale.crm.institution.domain;

import java.time.Instant;
import java.time.LocalDate;

public record InstitutionQualification(Long id, Long institutionId, String qualificationType, String title,
                                       String licenseNoCipher, Long fileId, LocalDate validFrom, LocalDate validTo,
                                       String status, Long createdBy, Long reviewedBy, Instant reviewedAt,
                                       Instant createdAt, Long version) {
}
