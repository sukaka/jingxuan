package com.whale.crm.ai.api.dto;

public record AiQualitySummaryResponse(long pendingReview, long riskSuggested, long failed, long confirmed,
                                       String riskNotice) { }
