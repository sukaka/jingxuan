package com.whale.crm.fulfillment.domain;

import java.time.Instant;

public record AppointmentItem(Long id, Long appointmentId, Long entitlementId, Long projectId,
                              Integer plannedCount, Instant createdAt, Long version) {
}
