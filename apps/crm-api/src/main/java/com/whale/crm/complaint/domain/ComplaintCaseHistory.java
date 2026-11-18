package com.whale.crm.complaint.domain;

import java.time.Instant;

public record ComplaintCaseHistory(Long id, long tenantId, long complaintCaseId, String action, String fromStatus,
                                   String toStatus, long actorUserId, String reasonMasked, Instant createdAt) {
}
