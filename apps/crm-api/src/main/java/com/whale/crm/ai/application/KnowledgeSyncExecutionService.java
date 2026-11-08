package com.whale.crm.ai.application;

import com.whale.crm.ai.domain.KnowledgeDocument;
import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.ai.domain.KnowledgeSyncJob;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.application.KnowledgeTextContent;
import com.whale.crm.material.application.MaterialApplicationService;
import com.whale.crm.material.domain.MaterialRevision;
import com.whale.crm.platform.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@Service
public class KnowledgeSyncExecutionService {
    private static final long MAX_TEXT_BYTES = 1_048_576L;
    private final KnowledgeRepository repository;
    private final MaterialApplicationService materialService;
    private final FileApplicationService fileService;
    private final DifyKnowledgeConfigService configService;
    private final KnowledgeWorkerClient workerClient;
    private final TransactionTemplate transactions;

    public KnowledgeSyncExecutionService(KnowledgeRepository repository, MaterialApplicationService materialService,
                                         FileApplicationService fileService, DifyKnowledgeConfigService configService,
                                         KnowledgeWorkerClient workerClient, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.materialService = materialService;
        this.fileService = fileService;
        this.configService = configService;
        this.workerClient = workerClient;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void execute(long tenantId, long jobId, String owner) {
        KnowledgeSyncJob job = repository.findSyncJobById(tenantId, jobId).orElse(null);
        KnowledgeDocument document = job == null ? null : repository.findDocumentById(tenantId, job.documentId()).orElse(null);
        if (job == null || document == null || !"RUNNING".equals(job.status()) || !owner.equals(job.leaseOwner())
                || "DISABLED".equals(document.status())) {
            return;
        }
        try {
            KnowledgeWorkerClient.DatasetProviderConfig config = configService.datasetWorkerConfig(tenantId);
            if (config == null) {
                fail(job, document, "SYNC_DISABLED");
                return;
            }
            if (job.difyBatch() != null) {
                pollIndexing(job, document, config);
            } else {
                sync(job, document, config);
            }
        } catch (AiWorkerException ex) {
            if (ex.terminal()) {
                fail(job, document, "WORKER_REJECTED");
            } else {
                retry(job, document, "WORKER_UNAVAILABLE");
            }
        } catch (BusinessException ex) {
            fail(job, document, "SOURCE_INVALID");
        } catch (Exception ex) {
            if (isDisabled(tenantId, document.id())) {
                return;
            }
            retry(job, document, "SYNC_FAILED");
        }
    }

    private boolean isDisabled(long tenantId, long documentId) {
        return repository.findDocumentById(tenantId, documentId)
                .map(document -> "DISABLED".equals(document.status())).orElse(false);
    }

    private void sync(KnowledgeSyncJob job, KnowledgeDocument document,
                      KnowledgeWorkerClient.DatasetProviderConfig config) {
        if (job.attemptCount() >= job.maxAttempts()) {
            fail(job, document, "ATTEMPTS_EXHAUSTED");
            return;
        }
        MaterialRevision revision = materialService.getApprovedRevisionForKnowledge(
                job.tenantId(), document.materialId(), job.revisionId());
        KnowledgeTextContent content = fileService.readKnowledgeText(
                job.tenantId(), revision.fileId(), revision.institutionId(), MAX_TEXT_BYTES);
        if (!job.contentHash().equals(content.metadata().sha256())) {
            fail(job, document, "SOURCE_CHANGED");
            return;
        }
        KnowledgeWorkerClient.DocumentMetadata metadata = new KnowledgeWorkerClient.DocumentMetadata(
                job.tenantId().toString(), document.materialId().toString(), revision.id().toString(), revision.revisionNo(),
                revision.categoryCode(), revision.institutionId() == null ? null : revision.institutionId().toString(),
                revision.projectId() == null ? null : revision.projectId().toString(), "STAFF_INTERNAL", job.contentHash());
        KnowledgeWorkerClient.DocumentSyncInput input = new KnowledgeWorkerClient.DocumentSyncInput(
                job.id().toString(), revision.title() + "-v" + revision.revisionNo() + ".md", content.text(), metadata,
                job.difyDocumentId());
        KnowledgeWorkerClient.DocumentSyncResult result = workerClient.syncDocument(config, input, requestId(job));
        if (job.difyDocumentId() != null && !job.difyDocumentId().equals(result.documentId())) {
            fail(job, document, "EXTERNAL_MAPPING_MISMATCH");
            return;
        }
        applyResult(job, document, config, result);
    }

    private void pollIndexing(KnowledgeSyncJob job, KnowledgeDocument document,
                              KnowledgeWorkerClient.DatasetProviderConfig config) {
        KnowledgeWorkerClient.DocumentSyncResult result = workerClient.documentStatus(
                config, job.difyDocumentId(), job.difyBatch(), requestId(job));
        applyResult(job, document, config, result);
    }

    private void applyResult(KnowledgeSyncJob job, KnowledgeDocument document,
                             KnowledgeWorkerClient.DatasetProviderConfig config,
                             KnowledgeWorkerClient.DocumentSyncResult result) {
        if ("AVAILABLE".equals(result.indexingStatus())) {
            complete(job, document, config.datasetId(), result.documentId());
        } else if ("FAILED".equals(result.indexingStatus())) {
            fail(job, document, "INDEXING_FAILED");
        } else {
            transactions.executeWithoutResult(status -> {
                requireUpdated(repository.markIndexing(job.tenantId(), job.id(), job.version(), job.leaseOwner(),
                        result.documentId(), result.batch(), Instant.now().plusSeconds(5)));
                markSyncingIfRequired(document);
            });
        }
    }

    private void complete(KnowledgeSyncJob job, KnowledgeDocument document, String datasetId, String difyDocumentId) {
        transactions.executeWithoutResult(status -> {
            requireUpdated(repository.markSucceeded(job.tenantId(), job.id(), job.version(), job.leaseOwner(), Instant.now()));
            requireUpdated(repository.activateDocument(document.tenantId(), document.id(), document.version(),
                    job.revisionId(), datasetId, difyDocumentId, Instant.now()));
        });
    }

    private void retry(KnowledgeSyncJob job, KnowledgeDocument document, String errorCode) {
        if (job.attemptCount() >= job.maxAttempts()) {
            fail(job, document, "ATTEMPTS_EXHAUSTED");
            return;
        }
        long delaySeconds = Math.min(300L, 5L * Math.max(1, job.attemptCount()));
        transactions.executeWithoutResult(status -> {
            requireUpdated(repository.markRetry(job.tenantId(), job.id(), job.version(), job.leaseOwner(), errorCode,
                    Instant.now().plusSeconds(delaySeconds)));
            markSyncingIfRequired(document);
        });
    }

    private void fail(KnowledgeSyncJob job, KnowledgeDocument document, String errorCode) {
        transactions.executeWithoutResult(status -> {
            requireUpdated(repository.markFailed(job.tenantId(), job.id(), job.version(), job.leaseOwner(), errorCode,
                    Instant.now()));
            if (document.activeRevisionId() == null) {
                requireUpdated(repository.markDocumentFailed(document.tenantId(), document.id(), document.version()));
            }
        });
    }

    private void markSyncingIfRequired(KnowledgeDocument document) {
        if (document.activeRevisionId() == null && !"SYNCING".equals(document.status())) {
            requireUpdated(repository.markDocumentSyncing(document.tenantId(), document.id(), document.version()));
        }
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("知识同步状态发生并发变化");
        }
    }

    private String requestId(KnowledgeSyncJob job) {
        return "knowledge-sync-" + job.id() + "-" + job.attemptCount();
    }
}
