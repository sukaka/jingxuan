package com.whale.crm.ai.application;

import com.whale.crm.ai.domain.AiJob;
import com.whale.crm.ai.domain.AiJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class AiJobWorker {
    private final AiJobRepository repository;
    private final AiJobApplicationService service;
    private final AiProviderConfigService providerConfigService;
    private final TransactionTemplate transactions;
    private final int batchSize;
    private final long claimTimeoutMs;
    private final String owner = UUID.randomUUID().toString();

    public AiJobWorker(AiJobRepository repository, AiJobApplicationService service,
                       AiProviderConfigService providerConfigService, PlatformTransactionManager transactionManager,
                       @Value("${app.ai.worker.batch-size:10}") int batchSize,
                       @Value("${app.ai.worker.claim-timeout-ms:45000}") long claimTimeoutMs) {
        this.repository = repository;
        this.service = service;
        this.providerConfigService = providerConfigService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.claimTimeoutMs = claimTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${app.ai.worker.delay-ms:1000}")
    public void scheduled() {
        runOnce();
    }

    public int runOnce() {
        int processed = 0;
        for (Long tenantId : repository.claimTenantIds(Instant.now())) {
            if (!providerConfigService.workerEnabled(tenantId)) {
                continue;
            }
            for (AiJob candidate : repository.claimable(tenantId, Instant.now(), batchSize)) {
                Instant now = Instant.now();
                Boolean claimed = transactions.execute(status -> repository.claim(tenantId, candidate.id(), candidate.version(), owner, now, now.plusMillis(claimTimeoutMs)) == 1);
                if (!Boolean.TRUE.equals(claimed)) {
                    continue;
                }
                service.execute(tenantId, candidate.id());
                processed++;
            }
        }
        return processed;
    }
}
