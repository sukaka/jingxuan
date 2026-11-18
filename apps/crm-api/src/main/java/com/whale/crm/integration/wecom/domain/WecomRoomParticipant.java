package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomRoomParticipant(Long id, String corpId, String roomId, String wecomUserid,
                                   String participantType, Long staffUserId, String externalUserid,
                                   String status, Instant confirmedAt, Instant removedAt, long version) {
    public boolean active() {
        return "ACTIVE".equals(status);
    }

    public boolean staff() {
        return "STAFF".equals(participantType);
    }

    public boolean external() {
        return "EXTERNAL".equals(participantType);
    }

    public WecomRoomParticipant withStatus(String nextStatus, Instant removedAt) {
        return new WecomRoomParticipant(id, corpId, roomId, wecomUserid, participantType, staffUserId, externalUserid,
                nextStatus, confirmedAt, removedAt, version);
    }
}
