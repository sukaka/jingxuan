package com.whale.crm.ai.api.dto;

import com.whale.crm.ai.domain.KnowledgeDocument;
import com.whale.crm.ai.domain.KnowledgeSyncJob;

import java.time.Instant;

public record KnowledgeDocumentResponse(Long id, Long materialId, Long activeRevisionId, String datasetId,
                                        String difyDocumentId, String status, Instant publishedAt, Long latestJobId,
                                        String latestJobStatus, Integer attemptCount, String lastErrorMasked, Long version) {
    public static KnowledgeDocumentResponse from(KnowledgeDocument document, KnowledgeSyncJob latestJob) {
        return new KnowledgeDocumentResponse(document.id(), document.materialId(), document.activeRevisionId(),
                document.datasetId(), document.difyDocumentId(), document.status(), document.publishedAt(),
                latestJob == null ? null : latestJob.id(), latestJob == null ? null : latestJob.status(),
                latestJob == null ? null : latestJob.attemptCount(),
                latestJob == null ? null : latestJob.lastErrorMasked(), document.version());
    }
}
