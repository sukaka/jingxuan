package com.whale.crm.fulfillment.domain;

import java.time.Instant;

public record Appointment(Long id, String appointmentNo, Long customerId, Long institutionId,
                          Long ownerStaffId, Long teamId, Instant scheduledAt, String remark,
                          String status, Long createdBy, Instant createdAt, Long version) {
}
