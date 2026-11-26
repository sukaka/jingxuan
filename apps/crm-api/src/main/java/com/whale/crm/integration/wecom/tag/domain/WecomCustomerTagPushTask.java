package com.whale.crm.integration.wecom.tag.domain;

import java.time.Instant;

/**
 * CRM 客户标签推送至企微任务实体（crm_wecom_customer_tag_push_task）。
 */
public record WecomCustomerTagPushTask(
        Long id,
        long tenantId,
        String corpId,
        long customerId,
        long wecomContactId,
        String wecomUserid,
        String externalUserid,
        String addTagIdsJson,
        String removeTagIdsJson,
        String status,
        int attempts,
        int maxAttempts,
        Instant nextRetryAt,
        Instant leaseUntil,
        String errorMasked,
        String triggerSource,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_RETRYABLE = "RETRYABLE";
    public static final String STATUS_FAILED = "FAILED";
}
