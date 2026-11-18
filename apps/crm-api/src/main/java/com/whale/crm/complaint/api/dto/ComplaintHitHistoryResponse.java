package com.whale.crm.complaint.api.dto;

import com.whale.crm.complaint.domain.ComplaintHitHistory;

import java.time.Instant;

public record ComplaintHitHistoryResponse(long id, long complaintHitId, String action, String fromStatus,
                                          String toStatus, Long actorUserId, String reasonMasked,
                                          Instant createdAt) {
    public static ComplaintHitHistoryResponse from(ComplaintHitHistory history) {
        return new ComplaintHitHistoryResponse(history.id(), history.complaintHitId(), history.action(),
                history.fromStatus(), history.toStatus(), history.actorUserId(), history.reasonMasked(),
                history.createdAt());
    }
}
