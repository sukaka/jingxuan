package com.whale.crm.integration.wecom.api.dto;

import java.time.Instant;

public record WecomMessageView(Long id, String corpId, String msgId, Long archiveSeq, Long customerId,
                               String externalUserid, Long staffUserId, String conversationId, String messageType,
                               String senderType, Instant sentAt, String contentObjectKey, String consentSnapshot,
                               String currentConsentStatus, String status) {
}
