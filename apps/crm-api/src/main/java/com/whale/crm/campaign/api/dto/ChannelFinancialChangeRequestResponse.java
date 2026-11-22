package com.whale.crm.campaign.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

public record ChannelFinancialChangeRequestResponse(
        Long id, Long channelId, String channelNo, String channelName, String status,
        Long requestedBy, String requestedByName, Instant requestedAt, String reason,
        Long baseVersion, Long version, JsonNode beforeMasked, JsonNode afterMasked,
        BigDecimal commissionPercent, String settlementMethod,
        String paymentAccountName, String paymentAccountNo, String paymentBankName,
        Long reviewerUserId, String reviewerName, Instant reviewedAt, String reviewComment) {
}
