package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.annotation.JsonProperty;

public record YouzanOrderLine(@JsonProperty("external_sku_id") String externalSkuId,
                              @JsonProperty("quantity") Integer quantity) {
}
