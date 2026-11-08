package com.whale.crm.product.domain;

import java.time.Instant;

public record PackageVersion(Long id, Long packageId, Integer versionNo, String selectionMode,
                             Integer choiceCount, String selectionRules, String status,
                             Instant createdAt, Long version) {
}
