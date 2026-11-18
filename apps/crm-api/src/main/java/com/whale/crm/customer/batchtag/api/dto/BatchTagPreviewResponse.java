package com.whale.crm.customer.batchtag.api.dto;

import java.util.List;

public record BatchTagPreviewResponse(String previewId, String operation, List<String> tagCodes,
                                      String sourceType, Long sourceId, List<BatchTagMemberResponse> items,
                                      long total, int page, int size) {
    public BatchTagPreviewResponse {
        tagCodes = List.copyOf(tagCodes);
        items = List.copyOf(items);
    }
}
