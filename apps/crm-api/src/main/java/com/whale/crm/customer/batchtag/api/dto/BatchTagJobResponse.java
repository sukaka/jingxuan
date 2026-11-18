package com.whale.crm.customer.batchtag.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BatchTagJobResponse(long id, String jobNo, long requestedBy, String operation,
                                  List<String> tagCodes, String sourceType, Long sourceId,
                                  Map<String, Object> sourceSnapshot, Map<String, Object> scopeSnapshot,
                                  String idempotencyKey, Long rollbackOfJobId, String status, int totalCount,
                                  int succeededCount, int noChangeCount, int skippedCount, int failedCount,
                                  int attemptCount, String lastErrorMasked, Instant createdAt,
                                  Instant startedAt, Instant finishedAt, long version) {
    public BatchTagJobResponse {
        tagCodes = List.copyOf(tagCodes);
        sourceSnapshot = sourceSnapshot == null ? null : Map.copyOf(sourceSnapshot);
        scopeSnapshot = scopeSnapshot == null ? null : Map.copyOf(scopeSnapshot);
    }
}
