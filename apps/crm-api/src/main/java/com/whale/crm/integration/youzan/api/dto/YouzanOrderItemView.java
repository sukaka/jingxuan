package com.whale.crm.integration.youzan.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record YouzanOrderItemView(
        @JsonProperty("external_sku_id") String externalSkuId,
        @JsonProperty("quantity") Integer quantity,
        String itemTitle,
        String skuSpec,
        Long priceFen,
        Long paymentFen,
        String outerSkuCode,
        Long crmSkuId,
        String crmSkuName) {
}
