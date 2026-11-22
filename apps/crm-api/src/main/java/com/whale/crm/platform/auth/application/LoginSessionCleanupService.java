package com.whale.crm.platform.auth.application;

import com.whale.crm.platform.auth.domain.LoginSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 定时分批清理过期服务端登录会话，防止 sys_login_session 长期膨胀。
 */
@Service
public class LoginSessionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(LoginSessionCleanupService.class);

    private final LoginSessionRepository loginSessionRepository;
    private final boolean enabled;
    private final long retentionDays;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public LoginSessionCleanupService(LoginSessionRepository loginSessionRepository,
                                      @Value("${app.security.auth.session-cleanup.enabled:true}") boolean enabled,
                                      @Value("${app.security.auth.session-cleanup.retention-days:7}") long retentionDays,
                                      @Value("${app.security.auth.session-cleanup.batch-size:500}") int batchSize,
                                      @Value("${app.security.auth.session-cleanup.max-batches-per-run:20}") int maxBatchesPerRun) {
        this.loginSessionRepository = loginSessionRepository;
        this.enabled = enabled;
        this.retentionDays = Math.max(0L, retentionDays);
        this.batchSize = Math.max(1, batchSize);
        this.maxBatchesPerRun = Math.max(1, maxBatchesPerRun);
    }

    @Scheduled(cron = "${app.security.auth.session-cleanup.cron:0 15 3 * * *}")
    public void cleanupScheduled() {
        if (!enabled) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deleted = cleanupExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("清理过期登录会话 {} 条，cutoff={}", deleted, cutoff);
        }
    }

    public int cleanupExpiredBefore(Instant cutoff) {
        int totalDeleted = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            int deleted = loginSessionRepository.deleteExpiredBefore(cutoff, batchSize);
            totalDeleted += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return totalDeleted;
    }
}
