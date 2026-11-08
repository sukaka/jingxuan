package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomMessage(Long id, String corpId, String msgId, Long archiveSeq, Long customerId,
                           String externalUserid, Long staffUserId, String conversationId, String messageType,
                           String senderType, Instant sentAt, String contentObjectKey, String contentSha256,
                           String consentSnapshot, String consentEvidenceRef, String status,
                           String currentConsentStatus, Long version) {
}
