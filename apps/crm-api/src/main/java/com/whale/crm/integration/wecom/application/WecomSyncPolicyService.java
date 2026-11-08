package com.whale.crm.integration.wecom.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.wecom.api.dto.WecomSyncPolicyRequest;
import com.whale.crm.integration.wecom.domain.WecomSyncPolicy;
import com.whale.crm.integration.wecom.domain.WecomSyncPolicyRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WecomSyncPolicyService {
    private final WecomSyncPolicyRepository repository;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public WecomSyncPolicyService(WecomSyncPolicyRepository repository, IdempotencyService idempotencyService,
                                  AuditService auditService, ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public WecomSyncPolicy get(long tenantId, String corpId) {
        return repository.find(tenantId, corpId).orElse(null);
    }

    public WecomSyncPolicy save(CurrentUser user, String corpId, WecomSyncPolicyRequest request, String key) {
        if (request.enabled() && !request.contactSyncEnabled() && !request.archiveSyncEnabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "至少启用一种企微同步类型");
        }
        if (request.enabled() && request.nextRunAt() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "启用定时同步时必须设置下次执行时间");
        }
        String scope = "wecom.sync.policy";
        String payload = json(request);
        IdempotencyRecord replay = idempotencyService.lookup(user.tenantId(), scope, key, payload).orElse(null);
        if (replay != null) {
            return repository.find(user.tenantId(), corpId).orElseThrow();
        }
        try {
            WecomSyncPolicy result = transactions.execute(status -> {
                WecomSyncPolicy existing = repository.findForUpdate(user.tenantId(), corpId).orElse(null);
                if (existing == null) {
                    repository.insert(new WecomSyncPolicy(null, user.tenantId(), corpId, request.enabled(),
                            request.contactSyncEnabled(), request.archiveSyncEnabled(), request.intervalSeconds(),
                            request.nextRunAt(), null, "NEVER", null, null));
                } else {
                    if (request.version() == null || !request.version().equals(existing.version())) {
                        throw new BusinessException(ErrorCode.CONFLICT, "企微同步策略版本已变化");
                    }
                    WecomSyncPolicy changed = new WecomSyncPolicy(existing.id(), user.tenantId(), corpId,
                            request.enabled(), request.contactSyncEnabled(), request.archiveSyncEnabled(),
                            request.intervalSeconds(), request.nextRunAt(), existing.lastScheduledAt(),
                            existing.lastStatus(), existing.lastError(), existing.version());
                    if (repository.update(changed) != 1) {
                        throw new BusinessException(ErrorCode.CONFLICT, "企微同步策略版本冲突");
                    }
                }
                WecomSyncPolicy saved = repository.find(user.tenantId(), corpId).orElseThrow();
                idempotencyService.recordSuccess(user.tenantId(), scope, key, "WECOM_SYNC_POLICY",
                        saved.id().toString(), payload);
                auditService.record(user.tenantId(), user.userId(), "WECOM_SYNC_POLICY_SAVE", "WECOM_SYNC_POLICY",
                        saved.id().toString(), existing, saved, null);
                return saved;
            });
            if (result == null) {
                throw new IllegalStateException("企微同步策略保存没有返回结果");
            }
            return result;
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微同步策略已存在，请刷新后重试");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("企微同步策略无法序列化", ex);
        }
    }
}
