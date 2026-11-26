package com.whale.crm.integration.youzan.domain;

import java.time.Instant;

public record YouzanMappingIssueEvent(
        Long issueId,
        Long eventId,
        String shopId,
        String externalOrderNo,
        String payloadMaskedJson,
        String payloadCipher,
        String providerPayloadMaskedJson,
        Instant receivedAt,
        String issueStatus,
        Long issueVersion
) {
}
