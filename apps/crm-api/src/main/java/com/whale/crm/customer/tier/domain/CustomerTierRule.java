package com.whale.crm.customer.tier.domain;

import java.time.Instant;

public record CustomerTierRule(Long id, long tenantId, String ruleCode, int versionNo, String ruleName,
                               String zoneId, String status, String metricConfigJson, Long createdBy,
                               Instant activatedAt, Instant retiredAt, Instant createdAt, Instant updatedAt,
                               long version) {
    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";
}
