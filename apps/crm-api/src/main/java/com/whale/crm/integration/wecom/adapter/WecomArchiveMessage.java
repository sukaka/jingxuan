package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.integration.wecom.media.domain.WecomMediaReference;

import java.time.Instant;
import java.util.List;

public record WecomArchiveMessage(String msgId, long seq, String externalUserid, Long staffUserId,
                                  String conversationId, String messageType, String senderType,
                                  Instant sentAt, String text, String processingBlockReason,
                                  List<WecomMediaReference> media, String previousMessageId,
                                  String conversationType, String senderWecomUserid, Long senderStaffUserId) {
    public WecomArchiveMessage {
        media = media == null ? List.of() : List.copyOf(media);
        conversationType = conversationType == null ? "DIRECT" : conversationType;
        if (senderWecomUserid == null && "DIRECT".equals(conversationType) && "CUSTOMER".equals(senderType)) {
            senderWecomUserid = externalUserid;
        }
        if (senderStaffUserId == null && "DIRECT".equals(conversationType) && "EMPLOYEE".equals(senderType)) {
            senderStaffUserId = staffUserId;
        }
    }

    public WecomArchiveMessage(String msgId, long seq, String externalUserid, Long staffUserId,
                               String conversationId, String messageType, String senderType,
                               Instant sentAt, String text) {
        this(msgId, seq, externalUserid, staffUserId, conversationId, messageType, senderType, sentAt, text, null);
    }

    public WecomArchiveMessage(String msgId, long seq, String externalUserid, Long staffUserId,
                               String conversationId, String messageType, String senderType,
                               Instant sentAt, String text, String processingBlockReason) {
        this(msgId, seq, externalUserid, staffUserId, conversationId, messageType, senderType, sentAt, text,
                processingBlockReason, List.of());
    }

    public WecomArchiveMessage(String msgId, long seq, String externalUserid, Long staffUserId,
                               String conversationId, String messageType, String senderType,
                               Instant sentAt, String text, String processingBlockReason,
                               List<WecomMediaReference> media) {
        this(msgId, seq, externalUserid, staffUserId, conversationId, messageType, senderType, sentAt, text,
                processingBlockReason, media, null);
    }

    public WecomArchiveMessage(String msgId, long seq, String externalUserid, Long staffUserId,
                               String conversationId, String messageType, String senderType,
                               Instant sentAt, String text, String processingBlockReason,
                               List<WecomMediaReference> media, String previousMessageId) {
        this(msgId, seq, externalUserid, staffUserId, conversationId, messageType, senderType, sentAt, text,
                processingBlockReason, media, previousMessageId, "DIRECT", null, null);
    }

    public static WecomArchiveMessage room(String msgId, long seq, String roomId, String messageType,
                                           String senderType, Instant sentAt, String text, String senderWecomUserid,
                                           Long senderStaffUserId, List<WecomMediaReference> media,
                                           String previousMessageId) {
        return new WecomArchiveMessage(msgId, seq, null, null, roomId, messageType, senderType, sentAt, text,
                null, media, previousMessageId, "ROOM", senderWecomUserid, senderStaffUserId);
    }

    public boolean room() {
        return "ROOM".equals(conversationType);
    }
}
