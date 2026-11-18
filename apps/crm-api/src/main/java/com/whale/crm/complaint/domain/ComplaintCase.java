package com.whale.crm.complaint.domain;

import java.time.Instant;

public record ComplaintCase(Long id, long tenantId, String complaintCaseNo, long complaintHitId, long customerId,
                            long ownerStaffIdSnapshot, Long teamIdSnapshot, Long recipientUserIdSnapshot,
                            String recipientSourceSnapshot, String status, Long assigneeUserId,
                            String resolutionMasked, Instant resolvedAt, Instant closedAt, String dismissReasonMasked,
                            Long createdBy, Instant createdAt, Instant updatedAt, long version) {
}
