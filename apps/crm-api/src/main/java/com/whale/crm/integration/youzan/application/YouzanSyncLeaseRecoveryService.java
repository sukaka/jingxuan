package com.whale.crm.integration.youzan.application;

import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@Service
public class YouzanSyncLeaseRecoveryService {
    private final YouzanConfigRepository repository;
    private final TransactionTemplate transactions;

    public YouzanSyncLeaseRecoveryService(YouzanConfigRepository repository,
                                          PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${YOUZAN_LEASE_RECOVERY_MS:60000}")
    public void recoverExpiredLeases() {
        transactions.executeWithoutResult(status -> {
            Instant now = Instant.now();
            repository.expireRunningJobs(now);
            repository.releaseExpiredShopLeases(now);
        });
    }
}
