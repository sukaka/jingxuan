package com.whale.crm.customer.domain;

import java.time.Instant;

public record MergeReversal(Long id, Long tenantId, Long mergeCaseId, String status, String reason, String reviewReason,
                            Long primaryVersion, Long secondaryVersion, Long requestedBy, Instant requestedAt,
                            Long reviewedBy, Instant reviewedAt, Instant executedAt, String warningMessage,
                            String errorMasked, Long version) {
}
