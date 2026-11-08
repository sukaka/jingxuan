package com.whale.crm.integration.wecom.api.dto;

public record WecomSyncResponse(String adapter, int received, int stored, int duplicates, int skipped,
                                long lastSeq, boolean hasMore, boolean dataGap) {
}
