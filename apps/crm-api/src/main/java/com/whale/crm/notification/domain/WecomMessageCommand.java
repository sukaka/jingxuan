package com.whale.crm.notification.domain;

public record WecomMessageCommand(long tenantId, long recipientUserId, String wecomUserId,
                                  String title, String contentMasked) {
}
