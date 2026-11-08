package com.whale.crm.material.domain;

import java.time.Instant;

public record MaterialListRow(Long id, Long tenantId, String materialNo, Long currentRevisionId,
                              Long materialCreatedBy, Instant materialCreatedAt, Instant materialUpdatedAt,
                              Long materialVersion, Instant materialOfflineAt, Long materialOfflineBy,
                              String materialOfflineReason, Long revisionId, Integer revisionNo, String title,
                              String summary, String categoryCode, Long institutionId, Long projectId,
                              String cityCode, String tagsJson, Long fileId, String changeNote, String status,
                              Long reviewerId, String reviewNote, Instant reviewedAt, Long createdBy,
                              Instant createdAt, Instant updatedAt, Long revisionVersion) {
    public Material material() {
        return new Material(id, tenantId, materialNo, title, categoryCode, institutionId, projectId, cityCode,
                summary, tagsJson, currentRevisionId, status, reviewerId, materialOfflineAt, materialOfflineBy,
                materialOfflineReason, materialCreatedBy, materialCreatedAt, materialUpdatedAt, materialVersion);
    }

    public MaterialRevision revision() {
        return new MaterialRevision(revisionId, tenantId, id, revisionNo, title, summary, categoryCode,
                institutionId, projectId, cityCode, tagsJson, fileId, changeNote, status, reviewerId,
                reviewNote, reviewedAt, createdBy, createdAt, updatedAt, revisionVersion);
    }
}
