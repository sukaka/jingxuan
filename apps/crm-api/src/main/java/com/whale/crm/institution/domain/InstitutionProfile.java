package com.whale.crm.institution.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record InstitutionProfile(Long id, String institutionNo, String fullName, String shortName,
                                 String cityCode, String businessTypeCode, String ownershipCode, String gradeCode,
                                 String address, String contactName, String contactPhoneCipher, String status,
                                 String settlementType, BigDecimal onlineRate, BigDecimal offlineRate,
                                 BigDecimal platformRate, String otherRateRule, Boolean chargesFee, Long depositFen,
                                 Boolean guaranteeRoi, String roiTerms, String settlementName,
                                 String bankNameCipher, String bankAccountCipher, String taxIdCipher,
                                 String introduction, Instant createdAt, Long version) {
}
