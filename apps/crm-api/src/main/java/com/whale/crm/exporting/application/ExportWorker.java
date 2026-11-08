package com.whale.crm.exporting.application;

import com.whale.crm.exporting.domain.ExportJob;
import com.whale.crm.exporting.domain.ExportJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class ExportWorker {
    private final ExportJobRepository repository;
    private final ExportExecutionService executionService;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final int batchSize;
    private final long leaseSeconds;
    private final String owner = UUID.randomUUID().toString();

    public ExportWorker(ExportJobRepository repository, ExportExecutionService executionService,
                        PlatformTransactionManager transactionManager,
                        @Value("${app.exports.worker.enabled:true}") boolean enabled,
                        @Value("${app.exports.worker.batch-size:5}") int batchSize,
                        @Value("${app.exports.worker.lease-seconds:60}") long leaseSeconds) {
        this.repository = repository;
        this.executionService = executionService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${app.exports.worker.delay-ms:1000}")
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    public int runOnce() {
        int processed = 0;
        Instant now = Instant.now();
        for (Long tenantId : repository.claimTenantIds(now)) {
            for (ExportJob candidate : repository.claimable(tenantId, now, batchSize)) {
                Instant claimedAt = Instant.now();
                Boolean claimed = transactions.execute(status -> repository.claim(tenantId, candidate.getId(),
                        candidate.getVersion(), owner, claimedAt, claimedAt.plusSeconds(leaseSeconds)) == 1);
                if (!Boolean.TRUE.equals(claimed)) {
                    continue;
                }
                executionService.execute(tenantId, candidate.getId(), owner);
                processed++;
            }
        }
        return processed;
    }
}
