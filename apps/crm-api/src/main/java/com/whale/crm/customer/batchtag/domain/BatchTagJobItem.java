package com.whale.crm.customer.batchtag.domain;

import java.time.Instant;

public record BatchTagJobItem(Long id, long tenantId, long jobId, long customerId, String customerNo,
                              String customerName, long ownerStaffId, Long teamId, String tagCodesJson,
                              String status, String failureCode, String reason, String beforeTagCodesJson,
                              String afterTagCodesJson, String changedTagCodesJson, int attemptCount,
                              Instant processedAt, long version) {
    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String NO_CHANGE = "NO_CHANGE";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";
}
