package com.whale.crm.complaint.api.dto;

import java.util.List;

public record ComplaintHitDetailResponse(ComplaintHitResponse complaintHit,
                                         List<ComplaintHitHistoryResponse> history) {
    public ComplaintHitDetailResponse {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
