package com.whale.crm.refund.domain;

public record RefundItem(Long id, Long tenantId, Long refundId, Long entitlementId,
                         Integer revokeCount, Long allocatedFen) { }
