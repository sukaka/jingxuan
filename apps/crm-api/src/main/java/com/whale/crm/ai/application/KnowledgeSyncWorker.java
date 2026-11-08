package com.whale.crm.ai.application;

import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.ai.domain.KnowledgeSyncJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class KnowledgeSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSyncWorker.class);
    private final KnowledgeRepository repository;
    private final KnowledgeSyncExecutionService executionService;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final int batchSize;
    private final long leaseMs;
    private final String owner = UUID.randomUUID().toString();

    public KnowledgeSyncWorker(KnowledgeRepository repository, KnowledgeSyncExecutionService executionService,
                               PlatformTransactionManager transactionManager,
                               @Value("${app.ai.knowledge.sync.worker.enabled:false}") boolean enabled,
                               @Value("${app.ai.knowledge.sync.worker.batch-size:5}") int batchSize,
                               @Value("${app.ai.knowledge.sync.worker.lease-ms:30000}") long leaseMs) {
        this.repository = repository;
        this.executionService = executionService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.leaseMs = leaseMs;
    }

    @Scheduled(fixedDelayString = "${app.ai.knowledge.sync.worker.delay-ms:1000}")
    public void scheduled() {
        runOnce();
    }

    public int runOnce() {
        if (!enabled) {
            return 0;
        }
        int processed = 0;
        Instant now = Instant.now();
        for (Long tenantId : repository.claimTenantIds(now)) {
            for (KnowledgeSyncJob candidate : repository.claimable(tenantId, now, batchSize)) {
                Instant claimedAt = Instant.now();
                boolean indexing = "INDEXING".equals(candidate.status());
                Boolean claimed = transactions.execute(status -> indexing
                        ? repository.claimIndexing(tenantId, candidate.id(), candidate.version(), owner,
                                claimedAt.plusMillis(leaseMs)) == 1
                        : repository.claimNew(tenantId, candidate.id(), candidate.version(), owner, claimedAt,
                                claimedAt.plusMillis(leaseMs)) == 1);
                if (!Boolean.TRUE.equals(claimed)) {
                    continue;
                }
                try {
                    executionService.execute(tenantId, candidate.id(), owner);
                    processed++;
                } catch (Exception ex) {
                    log.warn("知识同步任务执行失败，等待租约恢复 jobId={}", candidate.id());
                }
            }
        }
        return processed;
    }
}
