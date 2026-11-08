package com.whale.crm.institution.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateInstitutionProfileRequest(
        @NotBlank @Size(max = 48) String institutionNo,
        @NotBlank @Size(max = 160) String fullName,
        @Size(max = 96) String shortName,
        @Size(max = 64) String cityCode,
        @Size(max = 64) String businessTypeCode,
        @Size(max = 64) String ownershipCode,
        @Size(max = 16) String gradeCode,
        @Size(max = 255) String address,
        @Size(max = 128) String contactName,
        @Size(max = 32) String contactPhone,
        @NotBlank @Pattern(regexp = "MONTHLY|PER_REDEMPTION") String settlementType,
        @DecimalMin("0") @DecimalMax("1") BigDecimal onlineRate,
        @DecimalMin("0") @DecimalMax("1") BigDecimal offlineRate,
        @DecimalMin("0") @DecimalMax("1") BigDecimal platformRate,
        @Size(max = 2000) String otherRateRule,
        Boolean chargesFee,
        @Min(0) Long depositFen,
        Boolean guaranteeRoi,
        @Size(max = 2000) String roiTerms,
        @Size(max = 160) String settlementName,
        @Size(max = 160) String bankName,
        @Size(max = 64) String bankAccount,
        @Size(max = 64) String taxId,
        @Size(max = 4000) String introduction) {
}
