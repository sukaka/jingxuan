package com.whale.crm.complaint.api.dto;

import java.util.List;

public record ComplaintDetectionResponse(String sourceType, String sourceId, List<ComplaintHitResponse> hits) {
    public ComplaintDetectionResponse {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
