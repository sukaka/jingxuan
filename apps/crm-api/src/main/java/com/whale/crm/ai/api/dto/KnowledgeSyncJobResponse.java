package com.whale.crm.ai.api.dto;

import com.whale.crm.ai.domain.KnowledgeSyncJob;

public record KnowledgeSyncJobResponse(Long id, Long documentId, Long revisionId, String status, String contentHash,
                                       Integer attemptCount, String lastErrorMasked, Long version) {
    public static KnowledgeSyncJobResponse from(KnowledgeSyncJob job) {
        return new KnowledgeSyncJobResponse(job.id(), job.documentId(), job.revisionId(), job.status(), job.contentHash(),
                job.attemptCount(), job.lastErrorMasked(), job.version());
    }
}
