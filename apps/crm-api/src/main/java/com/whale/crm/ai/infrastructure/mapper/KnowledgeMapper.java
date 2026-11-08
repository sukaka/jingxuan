package com.whale.crm.ai.infrastructure.mapper;

import com.whale.crm.ai.domain.KnowledgeDocument;
import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.ai.domain.KnowledgeSyncJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface KnowledgeMapper extends KnowledgeRepository {
    @Override
    Optional<KnowledgeDocument> findDocumentForUpdate(@Param("tenantId") long tenantId, @Param("materialId") long materialId);

    @Override
    Optional<KnowledgeDocument> findDocumentById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    void insertDocument(@Param("tenantId") long tenantId, @Param("e") KnowledgeDocument document);

    @Override
    Optional<KnowledgeSyncJob> findSyncJobById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<KnowledgeSyncJob> findSyncJobByContent(@Param("tenantId") long tenantId, @Param("documentId") long documentId,
                                                    @Param("revisionId") long revisionId, @Param("contentHash") String contentHash);

    @Override
    Optional<KnowledgeSyncJob> findSyncJobByIdempotency(@Param("tenantId") long tenantId, @Param("requestedBy") long requestedBy,
                                                        @Param("key") String key);

    @Override
    void insertSyncJob(@Param("tenantId") long tenantId, @Param("e") KnowledgeSyncJob job);

    @Override
    List<Long> claimTenantIds(@Param("now") Instant now);

    @Override
    List<KnowledgeSyncJob> claimable(@Param("tenantId") long tenantId, @Param("now") Instant now, @Param("limit") int limit);

    @Override
    int markPending(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion);

    @Override
    int claimNew(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion,
                 @Param("owner") String owner, @Param("now") Instant now, @Param("until") Instant until);

    @Override
    int claimIndexing(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion,
                      @Param("owner") String owner, @Param("until") Instant until);

    @Override
    int markIndexing(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion,
                     @Param("owner") String owner, @Param("difyDocumentId") String difyDocumentId,
                     @Param("difyBatch") String difyBatch, @Param("nextPollAt") Instant nextPollAt);

    @Override
    int markSucceeded(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion,
                      @Param("owner") String owner, @Param("finishedAt") Instant finishedAt);

    @Override
    int markRetry(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion,
                  @Param("owner") String owner, @Param("errorMasked") String errorMasked, @Param("nextRetryAt") Instant nextRetryAt);

    @Override
    int markFailed(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion,
                   @Param("owner") String owner, @Param("errorMasked") String errorMasked, @Param("finishedAt") Instant finishedAt);

    @Override
    int markDocumentSyncing(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion);

    @Override
    int activateDocument(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion,
                         @Param("revisionId") long revisionId, @Param("datasetId") String datasetId,
                         @Param("difyDocumentId") String difyDocumentId, @Param("publishedAt") Instant publishedAt);

    @Override
    int markDocumentFailed(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion);

    @Override
    List<KnowledgeDocument> searchDocuments(@Param("tenantId") long tenantId, @Param("status") String status,
                                            @Param("offset") int offset, @Param("size") int size);

    @Override
    long countDocuments(@Param("tenantId") long tenantId, @Param("status") String status);

    @Override
    Optional<KnowledgeSyncJob> findLatestJob(@Param("tenantId") long tenantId, @Param("documentId") long documentId);

    @Override
    int retryJob(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion);

    @Override
    int disableDocument(@Param("tenantId") long tenantId, @Param("id") long id, @Param("expectedVersion") long expectedVersion);
}
