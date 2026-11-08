package com.whale.crm.integration.wecom.api.dto;

import java.time.Instant;

public record WecomContentView(Long messageRef, String messageType, Instant sentAt, String content) {
}
