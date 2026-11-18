package com.whale.crm.followup.application;

import com.whale.crm.followup.domain.FollowupAutomationRepository;
import com.whale.crm.followup.domain.FollowupBatch;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class MonthlyBatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(MonthlyBatchScheduler.class);

    private final FollowupAutomationRepository repository;
    private final MonthlyBatchExecutionService executionService;
    private final CurrentUserService currentUserService;
    private final boolean enabled;
    private final int batchSize;

    public MonthlyBatchScheduler(FollowupAutomationRepository repository,
                                 MonthlyBatchExecutionService executionService,
                                 CurrentUserService currentUserService,
                                 @Value("${app.followup.automation.scheduler-enabled:true}") boolean enabled,
                                 @Value("${app.followup.automation.batch-size:20}") int batchSize) {
        this.repository = repository;
        this.executionService = executionService;
        this.currentUserService = currentUserService;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.followup.automation.delay-ms:30000}",
            initialDelayString = "${app.followup.automation.initial-delay-ms:30000}")
    public void scheduled() {
        if (enabled) {
            runDueBatches();
        }
    }

    public int runDueBatches() {
        int processed = 0;
        Instant now = Instant.now();
        for (Long tenantId : repository.findRetryableBatchTenantIds(now)) {
            for (FollowupBatch batch : repository.findRetryableBatches(tenantId, now, batchSize)) {
                CurrentUser actor = currentUserService.loadById(batch.createdBy()).orElse(null);
                if (actor == null) {
                    continue;
                }
                try {
                    executionService.run(batch, actor, null);
                    processed++;
                } catch (RuntimeException ex) {
                    log.warn("月度回访批次自动重试未完成, tenantId={}, batchId={}, errorType={}",
                            tenantId, batch.id(), ex.getClass().getSimpleName());
                }
            }
        }
        return processed;
    }
}
