package com.whale.crm.institution.domain;

import java.time.Instant;
import java.time.LocalDate;

public record InstitutionContract(Long id, Long institutionId, String contractNo, Integer versionNo,
                                  String title, String contractType, LocalDate startDate, LocalDate endDate,
                                  Long fileId, Long amountFen, String notes, String status,
                                  Long previousContractId, Long createdBy, Instant createdAt, Long version) {
}
