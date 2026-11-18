package com.whale.crm.notification.api.dto;

import com.whale.crm.notification.domain.WecomMapping;

public record WecomMappingResponse(long id, String corpId, String wecomUserid, long staffUserId,
                                   boolean archiveEnabled, boolean messageEnabled, long version) {
    public static WecomMappingResponse from(WecomMapping mapping) {
        return new WecomMappingResponse(mapping.id(), mapping.corpId(), mapping.wecomUserid(), mapping.staffUserId(),
                mapping.archiveEnabled(), mapping.messageEnabled(), mapping.version());
    }
}
