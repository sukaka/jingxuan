package com.whale.crm.integration.wecom.adapter;

import java.time.Instant;

public record WecomArchiveMessage(String msgId, long seq, String externalUserid, long staffUserId,
                                  String conversationId, String messageType, String senderType,
                                  Instant sentAt, String text) {
}
