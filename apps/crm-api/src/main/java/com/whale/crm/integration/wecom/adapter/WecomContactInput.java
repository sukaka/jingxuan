package com.whale.crm.integration.wecom.adapter;

public record WecomContactInput(String externalUserid, long staffUserId, String consentStatus,
                                String consentEvidenceRef) {
}
