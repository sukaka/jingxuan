package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomRoom(Long id, long tenantId, String corpId, String roomId, String displayName, String status,
                        String sourceEvidenceRef, Instant membershipConfirmedAt, Long createdBy,
                        Instant createdAt, Instant updatedAt, long version) {
}
