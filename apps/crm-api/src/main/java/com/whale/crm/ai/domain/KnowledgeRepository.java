package com.whale.crm.ai.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KnowledgeRepository {
    Optional<KnowledgeDocument> findDocumentForUpdate(long tenantId, long materialId);

    Optional<KnowledgeDocument> findDocumentById(long tenantId, long id);

    void insertDocument(long tenantId, KnowledgeDocument document);

    Optional<KnowledgeSyncJob> findSyncJobById(long tenantId, long id);

    Optional<KnowledgeSyncJob> findSyncJobByContent(long tenantId, long documentId, long revisionId, String contentHash);

    Optional<KnowledgeSyncJob> findSyncJobByIdempotency(long tenantId, long requestedBy, String key);

    void insertSyncJob(long tenantId, KnowledgeSyncJob job);

    List<Long> claimTenantIds(Instant now);

    List<KnowledgeSyncJob> claimable(long tenantId, Instant now, int limit);

    int markPending(long tenantId, long id, long expectedVersion);

    int claimNew(long tenantId, long id, long expectedVersion, String owner, Instant now, Instant until);

    int claimIndexing(long tenantId, long id, long expectedVersion, String owner, Instant until);

    int markIndexing(long tenantId, long id, long expectedVersion, String owner, String difyDocumentId,
                     String difyBatch, Instant nextPollAt);

    int markSucceeded(long tenantId, long id, long expectedVersion, String owner, Instant finishedAt);

    int markRetry(long tenantId, long id, long expectedVersion, String owner, String errorMasked, Instant nextRetryAt);

    int markFailed(long tenantId, long id, long expectedVersion, String owner, String errorMasked, Instant finishedAt);

    int markDocumentSyncing(long tenantId, long id, long expectedVersion);

    int activateDocument(long tenantId, long id, long expectedVersion, long revisionId, String datasetId,
                         String difyDocumentId, Instant publishedAt);

    int markDocumentFailed(long tenantId, long id, long expectedVersion);

    List<KnowledgeDocument> searchDocuments(long tenantId, String status, int offset, int size);

    long countDocuments(long tenantId, String status);

    Optional<KnowledgeSyncJob> findLatestJob(long tenantId, long documentId);

    int retryJob(long tenantId, long id, long expectedVersion);

    int disableDocument(long tenantId, long id, long expectedVersion);
}
