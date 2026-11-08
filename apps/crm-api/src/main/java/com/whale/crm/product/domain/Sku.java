package com.whale.crm.product.domain;

import java.time.Instant;

public record Sku(Long id, String skuCode, Long packageVersionId, Long packageId, String packageName,
                  Integer versionNo, String selectionMode, String specName, Long salePriceFen,
                  String status, Instant onSaleAt, Instant offSaleAt, Instant createdAt, Long version) {
}
