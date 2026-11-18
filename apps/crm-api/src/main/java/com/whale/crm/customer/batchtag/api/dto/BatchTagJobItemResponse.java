package com.whale.crm.customer.batchtag.api.dto;

import java.time.Instant;
import java.util.List;

public record BatchTagJobItemResponse(long id, long customerId, String customerNo, String customerName,
                                      long ownerStaffId, Long teamId, List<String> tagCodes, String status,
                                      String failureCode, String reason, List<String> beforeTagCodes,
                                      List<String> afterTagCodes, List<String> changedTagCodes,
                                      int attemptCount, Instant processedAt, long version) {
    public BatchTagJobItemResponse {
        tagCodes = List.copyOf(tagCodes);
        beforeTagCodes = beforeTagCodes == null ? null : List.copyOf(beforeTagCodes);
        afterTagCodes = afterTagCodes == null ? null : List.copyOf(afterTagCodes);
        changedTagCodes = changedTagCodes == null ? null : List.copyOf(changedTagCodes);
    }
}
