package com.whale.crm.material.api.dto;

import com.whale.crm.material.domain.MaterialRevision;

import java.time.Instant;
import java.util.List;

public record MaterialRevisionResponse(Long id, Long materialId, Integer revisionNo, String title, String summary,
                                       String categoryCode, Long institutionId, Long projectId, String cityCode,
                                       List<String> tags, Long fileId, String changeNote, String status,
                                       Long reviewerId, String reviewNote, Instant reviewedAt, Long createdBy,
                                       Instant createdAt, Instant updatedAt, Long version) {
    public MaterialRevisionResponse {
        tags = tags == null ? null : List.copyOf(tags);
    }

    public static MaterialRevisionResponse from(MaterialRevision revision, List<String> tags) {
        return new MaterialRevisionResponse(revision.id(), revision.materialId(), revision.revisionNo(),
                revision.title(), revision.summary(), revision.categoryCode(), revision.institutionId(),
                revision.projectId(), revision.cityCode(), tags, revision.fileId(), revision.changeNote(),
                revision.status(), revision.reviewerId(), revision.reviewNote(), revision.reviewedAt(),
                revision.createdBy(), revision.createdAt(), revision.updatedAt(), revision.version());
    }
}
