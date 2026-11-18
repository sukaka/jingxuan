package com.whale.crm.notification;

import com.whale.crm.notification.application.NotificationDeliveryWorker;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationDelivery;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.notification.domain.NotificationRequest;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.outbox.relay.enabled=false", "app.notification.kafka.enabled=false",
        "app.notification.delivery.worker-enabled=false", "app.wecom.message.mode=DISABLED"
})
class NotificationDeliveryLeaseIT extends AbstractMysqlIT {
    @Autowired NotificationRequestService requestService;
    @Autowired NotificationRepository repository;
    @Autowired NotificationDeliveryWorker worker;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void concurrentWorkerLeaseHasOneWinnerAndExpiryBecomesUnknown() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Notification notification = requestService.request(new NotificationRequest(1L, 2L, "FOLLOWUP_DUE",
                "合成租约通知", "脱敏正文", "FOLLOWUP", suffix, "lease:" + suffix, null,
                Set.of(NotificationChannel.IN_APP)));
        repository.activateDeliveries(1L, notification.id());
        NotificationDelivery pending = repository.searchDeliveries(1L, notification.id(), null,
                null, null, 0, 1).getFirst();
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                String owner = "lease-it-" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    Instant now = Instant.now();
                    return repository.claimDelivery(1L, pending.id(), pending.version(), owner,
                            now, now.plusSeconds(60));
                }));
            }
            start.countDown();
            int claims = 0;
            for (Future<Integer> result : results) {
                claims += result.get();
            }
            assertThat(claims).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        NotificationDelivery claimed = repository.findDeliveryById(1L, pending.id()).orElseThrow();
        assertThat(repository.claimDelivery(1L, pending.id(), claimed.version(), "active-thief",
                Instant.now(), Instant.now().plusSeconds(60))).isZero();
        jdbcTemplate.update("UPDATE crm_notification_delivery SET claim_expires_at=? WHERE tenant_id=1 AND id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), pending.id());

        worker.runOnce();

        NotificationDelivery recovered = repository.findDeliveryById(1L, pending.id()).orElseThrow();
        assertThat(recovered.status()).isEqualTo("UNKNOWN");
        assertThat(recovered.nextAttemptAt()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_notification_attempt WHERE delivery_id=?",
                String.class, pending.id())).isEqualTo("UNKNOWN");
    }
}
