package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomRecallEvent(Long id, long tenantId, String corpId, String recallMessageId,
                               String originalMessageId, long recallSeq, String externalUserid, Long staffUserId,
                               String senderType, Instant occurredAt, String status, Long originalMessageRef,
                               Instant createdAt, Instant appliedAt, long version,
                               String conversationType, String conversationId, String senderWecomUserid) {
    public WecomRecallEvent(Long id, long tenantId, String corpId, String recallMessageId,
                            String originalMessageId, long recallSeq, String externalUserid, Long staffUserId,
                            String senderType, Instant occurredAt, String status, Long originalMessageRef,
                            Instant createdAt, Instant appliedAt, long version) {
        this(id, tenantId, corpId, recallMessageId, originalMessageId, recallSeq, externalUserid, staffUserId,
                senderType, occurredAt, status, originalMessageRef, createdAt, appliedAt, version,
                "DIRECT", externalUserid, "CUSTOMER".equals(senderType) ? externalUserid : null);
    }
}
