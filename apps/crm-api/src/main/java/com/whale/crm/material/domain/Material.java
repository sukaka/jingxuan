package com.whale.crm.material.domain;

import java.time.Instant;

public record Material(Long id, Long tenantId, String materialNo, String title, String categoryCode,
                       Long institutionId, Long projectId, String cityCode, String summary, String tagsJson,
                       Long currentRevisionId, String reviewStatus, Long reviewerId,
                       Instant offlineAt, Long offlineBy, String offlineReason, Long createdBy,
                       Instant createdAt, Instant updatedAt, Long version) {
}
