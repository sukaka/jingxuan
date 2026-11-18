package com.whale.crm.integration.wecom.api.dto;

public record WecomRoomParticipantView(String wecomUserid, String participantType, Long staffUserId,
                                       String externalUserid, String status) {
    public static WecomRoomParticipantView from(com.whale.crm.integration.wecom.domain.WecomRoomParticipant value) {
        return new WecomRoomParticipantView(value.wecomUserid(), value.participantType(), value.staffUserId(),
                value.externalUserid(), value.status());
    }
}
