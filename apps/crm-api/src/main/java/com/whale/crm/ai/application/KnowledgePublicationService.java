package com.whale.crm.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.KnowledgeSyncJobResponse;
import com.whale.crm.ai.api.dto.PublishKnowledgeDocumentRequest;
import com.whale.crm.ai.domain.KnowledgeDocument;
import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.ai.domain.KnowledgeSyncJob;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.application.KnowledgeTextContent;
import com.whale.crm.material.application.MaterialApplicationService;
import com.whale.crm.material.domain.MaterialRevision;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.application.OutboxService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

@Service
public class KnowledgePublicationService {
    private static final String PUBLISH_SCOPE = "ai.knowledge.publish";
    private static final long MAX_TEXT_BYTES = 1_048_576L;

    private final KnowledgeRepository repository;
    private final MaterialApplicationService materialService;
    private final FileApplicationService fileService;
    private final DifyKnowledgeConfigService configService;
    private final OutboxService outboxService;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public KnowledgePublicationService(KnowledgeRepository repository, MaterialApplicationService materialService,
                                       FileApplicationService fileService, DifyKnowledgeConfigService configService,
                                       OutboxService outboxService, IdempotencyService idempotencyService,
                                       AuditService auditService, ObjectMapper objectMapper,
                                       PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.materialService = materialService;
        this.fileService = fileService;
        this.configService = configService;
        this.outboxService = outboxService;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public KnowledgeSyncJobResponse publish(CurrentUser user, PublishKnowledgeDocumentRequest request, String key) {
        String payload = json(request);
        var replay = idempotencyService.lookup(user.tenantId(), PUBLISH_SCOPE, key, payload);
        if (replay.isPresent()) {
            long jobId = Long.parseLong(replay.get().getResourceId());
            KnowledgeSyncJob job = repository.findSyncJobById(user.tenantId(), jobId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "发布任务不存在，请重新发起"));
            return KnowledgeSyncJobResponse.from(job);
        }
        if (!configService.syncEnabled(user.tenantId())) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_DISABLED, "知识资料同步未启用");
        }
        MaterialRevision revision = materialService.getApprovedRevisionForKnowledge(
                user.tenantId(), user, request.materialId(), request.revisionId());
        if (!revision.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "素材修订版本已变化，请刷新后重试");
        }
        KnowledgeTextContent content = fileService.readKnowledgeText(
                user.tenantId(), revision.fileId(), revision.institutionId(), MAX_TEXT_BYTES);
        String datasetId = configService.datasetId(user.tenantId());
        try {
            return transactions.execute(status -> {
                KnowledgeDocument document = repository.findDocumentForUpdate(user.tenantId(), revision.materialId())
                        .orElse(null);
                if (document == null) {
                    repository.insertDocument(user.tenantId(), new KnowledgeDocument(null, user.tenantId(), revision.materialId(),
                            null, datasetId, null, "WAITING", user.userId(), null, null, null, 0L));
                    document = repository.findDocumentForUpdate(user.tenantId(), revision.materialId()).orElseThrow();
                }
                if ("DISABLED".equals(document.status())) {
                    throw new BusinessException(ErrorCode.CONFLICT, "知识资料已停用，不能通过再次发布隐式启用");
                }
                KnowledgeSyncJob existing = repository.findSyncJobByContent(user.tenantId(), document.id(),
                        revision.id(), content.metadata().sha256()).orElse(null);
                if (existing != null) {
                    idempotencyService.recordSuccess(user.tenantId(), PUBLISH_SCOPE, key, "KNOWLEDGE_SYNC_JOB",
                            existing.id().toString(), payload);
                    return KnowledgeSyncJobResponse.from(existing);
                }
                KnowledgeSyncJob input = new KnowledgeSyncJob(null, user.tenantId(), document.id(), revision.id(),
                        content.metadata().sha256(), "WAITING", 0, 5, null, null, null, document.difyDocumentId(),
                        null, null, user.userId(), key, IdempotencyService.sha256(payload), null, null, null, null, 0L);
                repository.insertSyncJob(user.tenantId(), input);
                KnowledgeSyncJob job = repository.findSyncJobByContent(user.tenantId(), document.id(), revision.id(),
                        content.metadata().sha256()).orElseThrow();
                outboxService.append(user.tenantId(), "knowledge.sync.requested.v1:" + job.id(),
                        "knowledge.sync.requested.v1", "knowledge_document", job.id().toString(),
                        Map.of("jobId", job.id(), "documentId", document.id()));
                idempotencyService.recordSuccess(user.tenantId(), PUBLISH_SCOPE, key, "KNOWLEDGE_SYNC_JOB",
                        job.id().toString(), payload);
                auditService.record(user.tenantId(), user.userId(), "KNOWLEDGE_PUBLISH_REQUEST", "KNOWLEDGE_SYNC_JOB",
                        job.id().toString(), null, Map.of("documentId", document.id(), "revisionId", revision.id(),
                                "contentHash", content.metadata().sha256(), "status", "WAITING"), null);
                return KnowledgeSyncJobResponse.from(job);
            });
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "相同资料正在发布，请查询已有任务");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("请求无法序列化", ex);
        }
    }
}
