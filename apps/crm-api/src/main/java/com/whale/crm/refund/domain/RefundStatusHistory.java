package com.whale.crm.refund.domain;

import java.time.Instant;

public record RefundStatusHistory(Long id, Long tenantId, Long refundId, String fromStatus,
                                  String toStatus, Long actorUserId, String reason, Instant createdAt) { }
