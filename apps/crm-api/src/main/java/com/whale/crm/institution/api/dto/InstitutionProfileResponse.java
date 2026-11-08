package com.whale.crm.institution.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record InstitutionProfileResponse(Long id, String institutionNo, String fullName, String shortName,
                                         String cityCode, String businessTypeCode, String ownershipCode,
                                         String gradeCode, String address, String contactName,
                                         String contactPhoneMasked, String status, String settlementType,
                                         BigDecimal onlineRate, BigDecimal offlineRate, BigDecimal platformRate,
                                         String otherRateRule, Boolean chargesFee, Long depositFen,
                                         Boolean guaranteeRoi, String roiTerms, String settlementName,
                                         String bankNameMasked, String bankAccountMasked, String taxIdMasked,
                                         String introduction, Instant createdAt, Long version) {
}
