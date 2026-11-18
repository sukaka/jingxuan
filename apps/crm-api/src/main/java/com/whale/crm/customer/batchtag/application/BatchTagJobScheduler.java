package com.whale.crm.customer.batchtag.application;

import com.whale.crm.customer.batchtag.domain.BatchTagJob;
import com.whale.crm.customer.batchtag.domain.BatchTagJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class BatchTagJobScheduler {
    private static final Logger log = LoggerFactory.getLogger(BatchTagJobScheduler.class);

    private final BatchTagJobRepository repository;
    private final BatchTagJobExecutionService executionService;
    private final boolean enabled;
    private final int batchSize;

    public BatchTagJobScheduler(BatchTagJobRepository repository, BatchTagJobExecutionService executionService,
            @Value("${app.batch-tag.recovery.enabled:true}") boolean enabled,
            @Value("${app.batch-tag.recovery.batch-size:20}") int batchSize) {
        this.repository = repository;
        this.executionService = executionService;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.batch-tag.recovery.delay-ms:30000}",
            initialDelayString = "${app.batch-tag.recovery.initial-delay-ms:30000}")
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    public int runOnce() {
        int processed = 0;
        Instant now = Instant.now();
        for (Long tenantId : repository.findExpiredJobTenantIds(now)) {
            for (BatchTagJob candidate : repository.findExpiredJobs(tenantId, now, batchSize)) {
                try {
                    executionService.run(candidate, null);
                    processed++;
                } catch (RuntimeException ex) {
                    log.warn("批量重打标租约恢复未完成, tenantId={}, jobId={}, errorType={}",
                            tenantId, candidate.id(), ex.getClass().getSimpleName());
                }
            }
        }
        return processed;
    }
}
