package com.whale.crm.complaint.api.dto;

import com.whale.crm.complaint.domain.ComplaintHit;

import java.time.Instant;

public record ComplaintHitResponse(long id, long customerId, long ownerStaffIdSnapshot, Long teamIdSnapshot,
                                   String sourceType, String sourceId, Long keywordId, String keywordSnapshot,
                                   String evidenceMasked, String status, String routingStatus, String routingError,
                                   Long recipientUserIdSnapshot, String recipientSourceSnapshot,
                                   long policyVersionSnapshot, Long complaintCaseId, String reviewReasonMasked,
                                   Long reviewedBy, Instant reviewedAt, Instant createdAt, Instant updatedAt,
                                   long version) {
    public static ComplaintHitResponse from(ComplaintHit hit) {
        return new ComplaintHitResponse(hit.id(), hit.customerId(), hit.ownerStaffIdSnapshot(), hit.teamIdSnapshot(), hit.sourceType(), hit.sourceId(), hit.keywordId(),
                hit.keywordSnapshot(), hit.evidenceMasked(), hit.status(), hit.routingStatus(), hit.routingError(),
                hit.recipientUserIdSnapshot(), hit.recipientSourceSnapshot(), hit.policyVersionSnapshot(),
                hit.complaintCaseId(), hit.reviewReasonMasked(), hit.reviewedBy(), hit.reviewedAt(), hit.createdAt(),
                hit.updatedAt(), hit.version());
    }
}
