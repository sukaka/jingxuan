package com.whale.crm.material.api.dto;

import com.whale.crm.material.domain.Material;
import com.whale.crm.material.domain.MaterialRevision;

import java.time.Instant;
import java.util.List;

public record MaterialResponse(Long id, String materialNo, String title, String categoryCode, Long institutionId,
                               Long projectId, String cityCode, String summary, List<String> tags,
                               Long currentRevisionId, String reviewStatus, Instant offlineAt, Long offlineBy,
                               String offlineReason, Long createdBy, Instant createdAt, Instant updatedAt,
                               Long version, List<MaterialRevisionResponse> revisions) {
    public MaterialResponse {
        tags = tags == null ? null : List.copyOf(tags);
        revisions = revisions == null ? null : List.copyOf(revisions);
    }

    public static MaterialResponse from(Material material, MaterialRevision displayedRevision, List<String> tags,
                                        List<MaterialRevisionResponse> revisions) {
        return new MaterialResponse(material.id(), material.materialNo(), displayedRevision.title(),
                displayedRevision.categoryCode(), displayedRevision.institutionId(), displayedRevision.projectId(),
                displayedRevision.cityCode(), displayedRevision.summary(), tags, material.currentRevisionId(),
                displayedRevision.status(), material.offlineAt(), material.offlineBy(), material.offlineReason(),
                material.createdBy(), material.createdAt(), material.updatedAt(), material.version(), revisions);
    }
}
