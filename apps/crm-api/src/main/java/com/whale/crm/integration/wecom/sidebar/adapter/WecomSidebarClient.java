package com.whale.crm.integration.wecom.sidebar.adapter;

import java.time.Instant;

public interface WecomSidebarClient {
    String oauthUserid(String code);

    WecomTicket ticket(boolean agentConfig);

    WecomUploadedMedia uploadTempMedia(String mediaType, String fileName, String contentType, byte[] content);

    record WecomTicket(String ticket, long expiresIn) {
    }

    record WecomUploadedMedia(String mediaId, String type, Instant createdAt) {
    }
}
