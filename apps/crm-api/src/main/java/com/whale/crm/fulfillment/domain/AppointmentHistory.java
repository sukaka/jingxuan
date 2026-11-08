package com.whale.crm.fulfillment.domain;

import java.time.Instant;

public record AppointmentHistory(Long id, Long appointmentId, String fromStatus, String toStatus,
                                 Long actorUserId, String reason, Instant createdAt) {
}
