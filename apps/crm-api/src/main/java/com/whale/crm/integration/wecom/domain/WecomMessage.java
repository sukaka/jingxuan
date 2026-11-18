package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomMessage(Long id, String corpId, String msgId, Long archiveSeq, Long customerId,
                           String externalUserid, Long staffUserId, String conversationId, String messageType,
                           String senderType, Instant sentAt, String contentObjectKey, String contentSha256,
                           String consentSnapshot, String consentEvidenceRef, String status, Instant recalledAt,
                           String recallMessageId, String currentConsentStatus, Long version,
                           String conversationType, String senderWecomUserid, Long senderStaffUserId,
                           Long roomRosterVersion) {
    public WecomMessage(Long id, String corpId, String msgId, Long archiveSeq, Long customerId,
                        String externalUserid, Long staffUserId, String conversationId, String messageType,
                        String senderType, Instant sentAt, String contentObjectKey, String contentSha256,
                        String consentSnapshot, String consentEvidenceRef, String status,
                        String currentConsentStatus, Long version) {
        this(id, corpId, msgId, archiveSeq, customerId, externalUserid, staffUserId, conversationId, messageType,
                senderType, sentAt, contentObjectKey, contentSha256, consentSnapshot, consentEvidenceRef, status,
                null, null, currentConsentStatus, version);
    }

    public WecomMessage(Long id, String corpId, String msgId, Long archiveSeq, Long customerId,
                        String externalUserid, Long staffUserId, String conversationId, String messageType,
                        String senderType, Instant sentAt, String contentObjectKey, String contentSha256,
                        String consentSnapshot, String consentEvidenceRef, String status, Instant recalledAt,
                        String recallMessageId, String currentConsentStatus, Long version) {
        this(id, corpId, msgId, archiveSeq, customerId, externalUserid, staffUserId, conversationId, messageType,
                senderType, sentAt, contentObjectKey, contentSha256, consentSnapshot, consentEvidenceRef, status,
                recalledAt, recallMessageId, currentConsentStatus, version, "DIRECT", null, null, null);
    }

    public boolean room() {
        return "ROOM".equals(conversationType);
    }
}
