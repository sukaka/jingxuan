package com.whale.crm.integration.wecom.h5card.api.dto;

import java.time.Instant;

public record WecomH5CardResponse(long id, long customerId, long projectId, String projectName, String title,
                                  String summary, String content, String link, String imageUrl, String status,
                                  Instant createdAt, long version) {
}
