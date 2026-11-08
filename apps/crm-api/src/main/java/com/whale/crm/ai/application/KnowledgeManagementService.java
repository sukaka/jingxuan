package com.whale.crm.ai.application;

import com.whale.crm.ai.api.dto.KnowledgeDocumentResponse;
import com.whale.crm.ai.api.dto.KnowledgeSyncJobResponse;
import com.whale.crm.ai.domain.KnowledgeDocument;
import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.ai.domain.KnowledgeSyncJob;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeManagementService {
    private static final String RETRY_SCOPE = "ai.knowledge.retry";
    private static final String DISABLE_SCOPE = "ai.knowledge.disable";
    private static final Set<String> STATUSES = Set.of("WAITING", "SYNCING", "ACTIVE", "FAILED", "DISABLED");

    private final KnowledgeRepository repository;
    private final DifyKnowledgeConfigService configService;
    private final KnowledgeWorkerClient workerClient;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final TransactionTemplate transactions;

    public KnowledgeManagementService(KnowledgeRepository repository, DifyKnowledgeConfigService configService,
                                      KnowledgeWorkerClient workerClient, IdempotencyService idempotencyService,
                                      AuditService auditService, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.configService = configService;
        this.workerClient = workerClient;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public PageResponse<KnowledgeDocumentResponse> search(CurrentUser user, String status, int page, int size) {
        if (!user.permissions().contains("ai:knowledge:read") && !user.permissions().contains("ai:knowledge:publish")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有知识资料查看权限");
        }
        if (page < 1 || size < 1 || size > 100 || (status != null && !status.isBlank() && !STATUSES.contains(status))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "知识资料查询参数无效");
        }
        String normalized = status == null || status.isBlank() ? null : status;
        int offset = Math.toIntExact((long) (page - 1) * size);
        List<KnowledgeDocumentResponse> items = repository.searchDocuments(user.tenantId(), normalized, offset, size)
                .stream().map(this::view).toList();
        return new PageResponse<>(items, repository.countDocuments(user.tenantId(), normalized), page, size);
    }

    public KnowledgeSyncJobResponse retry(CurrentUser user, long jobId, String key) {
        requirePublish(user);
        String payload = Long.toString(jobId);
        if (idempotencyService.lookup(user.tenantId(), RETRY_SCOPE, key, payload).isPresent()) {
            return job(user.tenantId(), jobId);
        }
        KnowledgeSyncJob job = repository.findSyncJobById(user.tenantId(), jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "同步任务不存在或不可见"));
        if (!Set.of("FAILED", "RETRY").contains(job.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有 FAILED 或 RETRY 任务可以人工重试");
        }
        return transactions.execute(status -> {
            if (repository.retryJob(user.tenantId(), jobId, job.version()) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "同步任务状态已变化");
            }
            idempotencyService.recordSuccess(user.tenantId(), RETRY_SCOPE, key, "KNOWLEDGE_SYNC_JOB",
                    Long.toString(jobId), payload);
            auditService.record(user.tenantId(), user.userId(), "KNOWLEDGE_SYNC_RETRY", "KNOWLEDGE_SYNC_JOB",
                    Long.toString(jobId), Map.of("status", job.status()), Map.of("status", "PENDING"), null);
            return job(user.tenantId(), jobId);
        });
    }

    public KnowledgeDocumentResponse disable(CurrentUser user, long documentId, String key) {
        requirePublish(user);
        String payload = Long.toString(documentId);
        if (idempotencyService.lookup(user.tenantId(), DISABLE_SCOPE, key, payload).isPresent()) {
            return view(document(user.tenantId(), documentId));
        }
        KnowledgeDocument document = document(user.tenantId(), documentId);
        if ("DISABLED".equals(document.status())) {
            idempotencyService.recordSuccess(user.tenantId(), DISABLE_SCOPE, key, "KNOWLEDGE_DOCUMENT",
                    Long.toString(documentId), payload);
            return view(document);
        }
        if (document.difyDocumentId() != null) {
            KnowledgeWorkerClient.DatasetProviderConfig config = configService.datasetWorkerConfig(user.tenantId());
            if (config == null) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_DISABLED, "知识资料同步未启用");
            }
            try {
                workerClient.disableDocument(config, document.difyDocumentId(), "knowledge-disable-" + documentId);
            } catch (AiWorkerException ex) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_FAILED, "Dify 文档停用失败，请稍后重试");
            }
        }
        return transactions.execute(status -> {
            if (repository.disableDocument(user.tenantId(), documentId, document.version()) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "知识资料状态已变化");
            }
            idempotencyService.recordSuccess(user.tenantId(), DISABLE_SCOPE, key, "KNOWLEDGE_DOCUMENT",
                    Long.toString(documentId), payload);
            auditService.record(user.tenantId(), user.userId(), "KNOWLEDGE_DOCUMENT_DISABLE", "KNOWLEDGE_DOCUMENT",
                    Long.toString(documentId), Map.of("status", document.status()), Map.of("status", "DISABLED"), null);
            return view(document(user.tenantId(), documentId));
        });
    }

    private KnowledgeSyncJobResponse job(long tenantId, long jobId) {
        return KnowledgeSyncJobResponse.from(repository.findSyncJobById(tenantId, jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "同步任务不存在或不可见")));
    }

    private KnowledgeDocument document(long tenantId, long documentId) {
        return repository.findDocumentById(tenantId, documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识资料不存在或不可见"));
    }

    private KnowledgeDocumentResponse view(KnowledgeDocument document) {
        KnowledgeSyncJob latest = repository.findLatestJob(document.tenantId(), document.id()).orElse(null);
        return KnowledgeDocumentResponse.from(document, latest);
    }

    private void requirePublish(CurrentUser user) {
        if (!user.permissions().contains("ai:knowledge:publish")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有知识资料发布权限");
        }
    }
}
