package com.whale.crm.integration.youzan.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record YouzanSkuMappingRequest(@NotBlank @Size(max = 64) String shopId,
                                      @NotBlank @Size(max = 128) String externalSkuId,
                                      @NotNull Long crmSkuId,
                                      @Min(0) Long version) {
}
