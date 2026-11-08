package com.whale.crm.customer.api.dto;

public record MergeReversalResponse(Long id, Long mergeCaseId, String status,
                                    String warningMessage, Long version) {
}
