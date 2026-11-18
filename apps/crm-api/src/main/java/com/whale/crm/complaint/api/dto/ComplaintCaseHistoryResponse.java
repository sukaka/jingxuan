package com.whale.crm.complaint.api.dto;

import com.whale.crm.complaint.domain.ComplaintCaseHistory;

import java.time.Instant;

public record ComplaintCaseHistoryResponse(long id, long complaintCaseId, String action, String fromStatus,
                                           String toStatus, long actorUserId, String reasonMasked,
                                           Instant createdAt) {
    public static ComplaintCaseHistoryResponse from(ComplaintCaseHistory history) {
        return new ComplaintCaseHistoryResponse(history.id(), history.complaintCaseId(), history.action(),
                history.fromStatus(), history.toStatus(), history.actorUserId(), history.reasonMasked(),
                history.createdAt());
    }
}
