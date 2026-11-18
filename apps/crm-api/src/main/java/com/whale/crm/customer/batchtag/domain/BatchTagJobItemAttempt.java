package com.whale.crm.customer.batchtag.domain;

import java.time.Instant;

public record BatchTagJobItemAttempt(Long id, long tenantId, long jobId, long itemId, int attemptNo,
                                     String status, String failureCode, String reason, String beforeTagCodesJson,
                                     String afterTagCodesJson, String changedTagCodesJson, long actorUserId,
                                     Instant createdAt) {
}
