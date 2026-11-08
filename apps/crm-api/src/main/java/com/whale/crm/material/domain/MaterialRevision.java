package com.whale.crm.material.domain;

import java.time.Instant;

public record MaterialRevision(Long id, Long tenantId, Long materialId, Integer revisionNo, String title,
                               String summary, String categoryCode, Long institutionId, Long projectId,
                               String cityCode, String tagsJson, Long fileId, String changeNote, String status,
                               Long reviewerId, String reviewNote, Instant reviewedAt, Long createdBy,
                               Instant createdAt, Instant updatedAt, Long version) {
}
