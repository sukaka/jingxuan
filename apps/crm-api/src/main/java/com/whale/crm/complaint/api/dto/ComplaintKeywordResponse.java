package com.whale.crm.complaint.api.dto;

import com.whale.crm.complaint.domain.ComplaintKeyword;

import java.time.Instant;

public record ComplaintKeywordResponse(long id, String keyword, String status, Long createdBy,
                                       Instant createdAt, Instant updatedAt, long version) {
    public static ComplaintKeywordResponse from(ComplaintKeyword keyword) {
        return new ComplaintKeywordResponse(keyword.id(), keyword.keyword(), keyword.status(), keyword.createdBy(),
                keyword.createdAt(), keyword.updatedAt(), keyword.version());
    }
}
