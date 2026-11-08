package com.whale.crm.integration.youzan.domain;

import java.time.Instant;

public record YouzanCustomerLink(Long id, String shopId, String externalCustomerId, Long customerId,
                                 String linkType, String confidence, String reason,
                                 Long createdBy, Instant createdAt, Long version) {
}
