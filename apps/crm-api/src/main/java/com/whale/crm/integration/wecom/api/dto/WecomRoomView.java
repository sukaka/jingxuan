package com.whale.crm.integration.wecom.api.dto;

import java.time.Instant;
import java.util.List;

public record WecomRoomView(String corpId, String roomId, String displayName, boolean enabled,
                            boolean archivable, Instant membershipConfirmedAt, long version,
                            List<WecomRoomParticipantView> participants) {
    public WecomRoomView {
        participants = participants == null ? List.of() : List.copyOf(participants);
    }
}
