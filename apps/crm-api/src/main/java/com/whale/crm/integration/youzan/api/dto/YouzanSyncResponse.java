package com.whale.crm.integration.youzan.api.dto;

public record YouzanSyncResponse(int received, int processed, int duplicates, int issues,
                                 String nextCursor, boolean hasMore) {
}
