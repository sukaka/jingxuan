package com.whale.crm.fulfillment.domain;

import org.apache.ibatis.annotations.AutomapConstructor;

import java.time.Instant;

public record Appointment(Long id, String appointmentNo, Long customerId, Long institutionId,
                          Long ownerStaffId, Long teamId, Instant scheduledAt, String remark,
                          String status, Instant confirmedAt, Instant checkedInAt, Instant serviceDoneAt,
                          Long createdBy, Instant createdAt, Long version) {
    @AutomapConstructor
    public Appointment {
    }

    public Appointment(Long id, String appointmentNo, Long customerId, Long institutionId,
                       Long ownerStaffId, Long teamId, Instant scheduledAt, String remark,
                       String status, Long createdBy, Instant createdAt, Long version) {
        this(id, appointmentNo, customerId, institutionId, ownerStaffId, teamId, scheduledAt, remark,
                status, null, null, null, createdBy, createdAt, version);
    }
}
