package com.whale.crm.fulfillment.domain;

import java.time.Instant;

public record RedemptionLine(Long id, Long redemptionId, Long appointmentItemId,
                             Long entitlementId, Long projectId, Integer count, Instant createdAt) {
}
