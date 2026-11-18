package com.whale.crm.integration.wecom.application;

import com.whale.crm.integration.wecom.domain.WecomRoom;
import com.whale.crm.integration.wecom.domain.WecomRoomParticipant;

public record WecomRoomAuthorization(WecomRoom room, WecomRoomParticipant sender,
                                     String gapType, String detail) {
    public boolean allowed() {
        return room != null && gapType == null;
    }

    public static WecomRoomAuthorization allow(WecomRoom room, WecomRoomParticipant sender) {
        return new WecomRoomAuthorization(room, sender, null, null);
    }

    public static WecomRoomAuthorization reject(String gapType, String detail) {
        return new WecomRoomAuthorization(null, null, gapType, detail);
    }
}
