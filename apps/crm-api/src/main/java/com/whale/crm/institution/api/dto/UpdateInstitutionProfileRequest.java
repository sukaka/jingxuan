package com.whale.crm.institution.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateInstitutionProfileRequest(
        @NotNull @Min(0) Long version,
        @Size(max = 96) String shortName,
        @Size(max = 64) String cityCode,
        @Size(max = 255) String address,
        @Size(max = 128) String contactName,
        @Size(max = 32) String contactPhone,
        @Size(max = 4000) String introduction) {
    @JsonAnySetter
    void rejectUnknownField(String field, Object value) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "敏感信息不支持直接修改，请提交变更审批");
    }
}
