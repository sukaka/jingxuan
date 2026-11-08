package com.whale.crm.institution.domain;

public record InstitutionMerchantResourceSummary(long institutionId, long doctorCount,
                                                 long equipmentCount, long caseCount) {
}
