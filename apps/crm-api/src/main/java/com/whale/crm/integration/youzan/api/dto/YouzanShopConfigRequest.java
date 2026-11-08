package com.whale.crm.integration.youzan.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record YouzanShopConfigRequest(@NotBlank @Size(max = 64) String shopId,
                                      @NotBlank @Size(max = 128) String shopName,
                                      @NotBlank @Size(max = 128) String clientId,
                                      @Size(max = 512) String clientSecret,
                                      @NotNull Boolean syncEnabled,
                                      Long version) {
}
