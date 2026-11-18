package com.whale.crm.complaint.api.dto;

import com.whale.crm.complaint.domain.ComplaintCase;

import java.time.Instant;

public record ComplaintCaseResponse(long id, String complaintCaseNo, long complaintHitId, long customerId,
                                    long ownerStaffIdSnapshot, Long teamIdSnapshot, Long recipientUserIdSnapshot,
                                    String recipientSourceSnapshot, String status, Long assigneeUserId,
                                    String resolutionMasked, Instant resolvedAt, Instant closedAt,
                                    String dismissReasonMasked, Long createdBy, Instant createdAt,
                                    Instant updatedAt, long version) {
    public static ComplaintCaseResponse from(ComplaintCase complaintCase) {
        return new ComplaintCaseResponse(complaintCase.id(), complaintCase.complaintCaseNo(),
                complaintCase.complaintHitId(), complaintCase.customerId(), complaintCase.ownerStaffIdSnapshot(),
                complaintCase.teamIdSnapshot(), complaintCase.recipientUserIdSnapshot(),
                complaintCase.recipientSourceSnapshot(), complaintCase.status(), complaintCase.assigneeUserId(),
                complaintCase.resolutionMasked(), complaintCase.resolvedAt(), complaintCase.closedAt(),
                complaintCase.dismissReasonMasked(), complaintCase.createdBy(), complaintCase.createdAt(),
                complaintCase.updatedAt(), complaintCase.version());
    }
}
