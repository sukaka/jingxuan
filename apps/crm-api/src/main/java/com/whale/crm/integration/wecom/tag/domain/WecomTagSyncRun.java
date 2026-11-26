package com.whale.crm.integration.wecom.tag.domain;

import java.time.Instant;

/**
 * 企微标签双向同步运行批次（crm_wecom_tag_sync_run）。
 */
public record WecomTagSyncRun(
        Long id,
        long tenantId,
        String corpId,
        String syncScope,
        String direction,
        String triggerSource,
        String adapterName,
        String status,
        int pulledCount,
        int pushedCount,
        int skippedCount,
        int failedCount,
        String errorMasked,
        Instant leaseUntil,
        Instant startedAt,
        Instant finishedAt,
        Long createdBy) {

    public static final String SCOPE_CATALOG = "CATALOG";
    public static final String SCOPE_CUSTOMER = "CUSTOMER";

    public static final String DIRECTION_PULL = "PULL";
    public static final String DIRECTION_PUSH = "PUSH";
    public static final String DIRECTION_BIDIRECTIONAL = "BIDIRECTIONAL";

    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_CALLBACK = "CALLBACK";
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_ERROR = "ERROR";
}
