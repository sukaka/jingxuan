package com.whale.crm.integration.youzan.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record YouzanCustomerLinkRequest(@NotBlank @Size(max = 64) String shopId,
                                        @NotBlank @Size(max = 128) String externalCustomerId,
                                        @NotNull Long customerId,
                                        @Min(0) Long version,
                                        @NotBlank @Size(max = 1000) String reason) {
}
