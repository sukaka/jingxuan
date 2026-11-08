package com.whale.crm.integration.youzan;

import com.whale.crm.integration.youzan.application.YouzanSyncLeaseRecoveryService;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class YouzanSyncLeaseRecoveryServiceTest {
    @Test
    void expiredJobsAndShopLeasesAreClosedTogether() {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        YouzanSyncLeaseRecoveryService service = new YouzanSyncLeaseRecoveryService(repository, transactionManager());

        service.recoverExpiredLeases();

        verify(repository).expireRunningJobs(any(Instant.class));
        verify(repository).releaseExpiredShopLeases(any(Instant.class));
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            public void commit(TransactionStatus status) {
            }

            public void rollback(TransactionStatus status) {
            }
        };
    }
}
