package com.whale.crm.product.domain;

import java.time.Instant;

public record ProductPackage(Long id, String packageCode, String packageName, String status,
                             Instant createdAt, Long version) {
}
