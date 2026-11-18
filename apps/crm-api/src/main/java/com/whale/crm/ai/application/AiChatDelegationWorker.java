package com.whale.crm.ai.application;

import com.whale.crm.ai.domain.AiChatDelegationRepository;
import com.whale.crm.ai.domain.AiChatDelegationRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class AiChatDelegationWorker {
    private final AiChatDelegationRepository repository;
    private final AiChatDelegationService service;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final int batchSize;
    private final long leaseMs;
    private final String owner = UUID.randomUUID().toString();

    public AiChatDelegationWorker(AiChatDelegationRepository repository, AiChatDelegationService service,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${app.ai.delegation.worker.enabled:false}") boolean enabled,
                                  @Value("${app.ai.delegation.worker.batch-size:10}") int batchSize,
                                  @Value("${app.ai.delegation.worker.lease-ms:45000}") long leaseMs) {
        this.repository = repository;
        this.service = service;
        this.transactions = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.leaseMs = leaseMs;
    }

    @Scheduled(fixedDelayString = "${app.ai.delegation.worker.delay-ms:1000}")
    public void scheduled() {
        runOnce();
    }

    public int runOnce() {
        if (!enabled) {
            return 0;
        }
        Instant now = Instant.now();
        for (AiChatDelegationRun expired : repository.findExpiredSending(now, batchSize)) {
            service.recoverUnknown(expired.tenantId(), expired.id());
        }
        int processed = 0;
        for (Long tenantId : repository.findWorkTenantIds(now)) {
            for (AiChatDelegationRun candidate : repository.findClaimableGeneration(tenantId, Instant.now(), batchSize)) {
                if (claimGeneration(candidate)) {
                    service.generate(tenantId, candidate.id(), owner);
                    processed++;
                }
            }
            for (AiChatDelegationRun candidate : repository.findClaimableSending(tenantId, Instant.now(), batchSize)) {
                if (claimSending(candidate)) {
                    service.send(tenantId, candidate.id(), owner);
                    processed++;
                }
            }
        }
        return processed;
    }

    private boolean claimGeneration(AiChatDelegationRun candidate) {
        Instant now = Instant.now();
        return Boolean.TRUE.equals(transactions.execute(status -> repository.claimGeneration(candidate.tenantId(),
                candidate.id(), candidate.version(), owner, now, now.plusMillis(leaseMs)) == 1));
    }

    private boolean claimSending(AiChatDelegationRun candidate) {
        Instant now = Instant.now();
        return Boolean.TRUE.equals(transactions.execute(status -> repository.claimSending(candidate.tenantId(),
                candidate.id(), candidate.version(), owner, now, now.plusMillis(leaseMs)) == 1));
    }
}
