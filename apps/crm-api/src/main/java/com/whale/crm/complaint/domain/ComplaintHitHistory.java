package com.whale.crm.complaint.domain;

import java.time.Instant;

public record ComplaintHitHistory(Long id, long tenantId, long complaintHitId, String action, String fromStatus,
                                  String toStatus, Long actorUserId, String reasonMasked, Instant createdAt) {
}
