package com.whale.crm.complaint.domain;

import java.time.Instant;

public record ComplaintHit(Long id, long tenantId, long customerId, long ownerStaffIdSnapshot, Long teamIdSnapshot,
                           String sourceType, String sourceId, Long keywordId, String keywordSnapshot,
                           String evidenceMasked, String status, String routingStatus, String routingError,
                           Long recipientUserIdSnapshot, String recipientSourceSnapshot, long policyVersionSnapshot,
                           Long complaintCaseId, String reviewReasonMasked, Long reviewedBy, Instant reviewedAt,
                           Instant createdAt, Instant updatedAt, long version) {
}
