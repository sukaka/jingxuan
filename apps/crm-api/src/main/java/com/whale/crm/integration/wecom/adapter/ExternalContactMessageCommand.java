package com.whale.crm.integration.wecom.adapter;

public record ExternalContactMessageCommand(long tenantId, String corpId, long staffUserId,
                                            String externalUserid, String text, String idempotencyKey) {
}
