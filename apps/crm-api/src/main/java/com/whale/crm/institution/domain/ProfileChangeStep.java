package com.whale.crm.institution.domain;

import java.time.Instant;

public record ProfileChangeStep(Long id, Long changeRequestId, Integer sequenceNo, String stepCode,
                                String status, Long reviewerUserId, Instant reviewedAt,
                                String comment, Instant createdAt) {
}
