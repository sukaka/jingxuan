package com.whale.crm.notification.application;

import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationAttempt;
import com.whale.crm.notification.domain.NotificationDelivery;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.notification.domain.WecomMessageCommand;
import com.whale.crm.notification.domain.WecomSendResult;
import com.whale.crm.notification.infrastructure.wecom.WecomMemberMappingException;
import com.whale.crm.notification.infrastructure.wecom.WecomMemberMappingResolver;
import com.whale.crm.notification.infrastructure.wecom.WecomMessageClientRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public final class NotificationDeliveryWorker {
    private final NotificationRepository repository;
    private final WecomMessageClientRouter messageClient;
    private final WecomMemberMappingResolver memberMappings;
    private final TransactionTemplate transactions;
    private final NotificationDeliveryProperties properties;
    private final String owner;

    @Autowired
    public NotificationDeliveryWorker(NotificationRepository repository, WecomMessageClientRouter messageClient,
                                      WecomMemberMappingResolver memberMappings,
                                      PlatformTransactionManager transactionManager,
                                      NotificationDeliveryProperties properties) {
        this(repository, messageClient, memberMappings, new TransactionTemplate(transactionManager), properties,
                UUID.randomUUID().toString());
    }

    NotificationDeliveryWorker(NotificationRepository repository, WecomMessageClientRouter messageClient,
                               WecomMemberMappingResolver memberMappings, TransactionTemplate transactions,
                               NotificationDeliveryProperties properties, String owner) {
        if (properties.batchSize() <= 0 || properties.leaseMs() <= 0 || properties.maxRetries() < 0
                || properties.retryInitialDelayMs() <= 0
                || properties.retryMaxDelayMs() < properties.retryInitialDelayMs()) {
            throw new IllegalArgumentException("通知投递 worker 配置无效");
        }
        this.repository = repository;
        this.messageClient = messageClient;
        this.memberMappings = memberMappings;
        this.transactions = transactions;
        this.properties = properties;
        this.owner = owner;
    }

    @Scheduled(fixedDelayString = "${app.notification.delivery.delay-ms:1000}")
    public void scheduled() {
        if (properties.workerEnabled()) {
            runOnce();
        }
    }

    public int runOnce() {
        recoverExpiredLeases();
        int processed = 0;
        Instant now = Instant.now();
        for (Long tenantId : repository.findDeliveryTenantIds(now)) {
            for (NotificationDelivery candidate : repository.findClaimableDeliveries(
                    tenantId, now, properties.batchSize())) {
                if (process(candidate)) {
                    processed++;
                }
                if (Thread.currentThread().isInterrupted()) {
                    return processed;
                }
            }
        }
        return processed;
    }

    void recoverExpiredLeases() {
        Instant now = Instant.now();
        for (NotificationDelivery candidate : repository.findExpiredDeliveries(now, properties.batchSize())) {
            transactions.executeWithoutResult(status -> {
                Instant finishedAt = Instant.now();
                if (repository.recoverExpiredDelivery(candidate.tenantId(), candidate.id(), candidate.version(),
                        candidate.claimOwner(), "WORKER_LEASE_EXPIRED_UNKNOWN", finishedAt) == 1) {
                    repository.insertAttempt(new NotificationAttempt(null, candidate.tenantId(), candidate.id(),
                            candidate.notificationId(), candidate.attemptCount(), candidate.channel(), "UNKNOWN",
                            null, null, "WORKER_LEASE_EXPIRED_UNKNOWN", "LEASE_RECOVERY", null, finishedAt));
                }
            });
        }
    }

    private boolean process(NotificationDelivery candidate) {
        Instant startedAt = Instant.now();
        Boolean claimed = transactions.execute(status -> repository.claimDelivery(candidate.tenantId(), candidate.id(),
                candidate.version(), owner, startedAt, startedAt.plusMillis(properties.leaseMs())) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            return false;
        }
        NotificationDelivery delivery = repository.findDeliveryById(candidate.tenantId(), candidate.id()).orElse(null);
        if (delivery == null || !owner.equals(delivery.claimOwner())
                || delivery.version() != candidate.version() + 1) {
            return false;
        }
        WecomSendResult result = send(delivery);
        finish(delivery, result);
        return true;
    }

    private WecomSendResult send(NotificationDelivery delivery) {
        if ("IN_APP".equals(delivery.channel())) {
            return new WecomSendResult(WecomSendResult.Status.SENT, "IN_APP", null, null);
        }
        Notification notification = repository.findById(delivery.tenantId(), delivery.notificationId()).orElse(null);
        if (notification == null) {
            return new WecomSendResult(WecomSendResult.Status.FAILED, null, null, "NOTIFICATION_NOT_FOUND");
        }
        try {
            String wecomUserId = memberMappings.resolve(delivery.tenantId(), delivery.recipientUserId());
            return messageClient.send(new WecomMessageCommand(delivery.tenantId(), delivery.recipientUserId(),
                    wecomUserId, notification.title(), notification.contentMasked()));
        } catch (WecomMemberMappingException ex) {
            return new WecomSendResult(WecomSendResult.Status.FAILED, null, null, ex.getMessage());
        } catch (Exception ex) {
            return new WecomSendResult(WecomSendResult.Status.UNKNOWN, null, null, "DELIVERY_OUTCOME_UNKNOWN");
        }
    }

    private void finish(NotificationDelivery delivery, WecomSendResult result) {
        transactions.executeWithoutResult(status -> {
            Instant finishedAt = Instant.now();
            int retryCount = delivery.automaticRetryCount();
            Instant nextAttemptAt = null;
            String deliveryStatus = switch (result.status()) {
                case SENT -> "SUCCEEDED";
                case MOCKED -> "MOCKED";
                case DISABLED -> "DISABLED";
                case FAILED -> "FAILED";
                case UNKNOWN -> "UNKNOWN";
                case RETRYABLE -> {
                    retryCount++;
                    if (retryCount <= properties.maxRetries()) {
                        nextAttemptAt = finishedAt.plusMillis(retryDelayMillis(retryCount));
                        yield "RETRY_WAIT";
                    }
                    yield "FAILED";
                }
            };
            String error = sanitize(result.errorMasked(), 512);
            if ("FAILED".equals(deliveryStatus) && result.status() == WecomSendResult.Status.RETRYABLE) {
                error = "MAX_AUTOMATIC_RETRIES_EXCEEDED";
            }
            if (repository.completeDelivery(delivery.tenantId(), delivery.id(), delivery.version(), owner,
                    deliveryStatus, result.provider(), sanitize(result.providerMessageId(), 255), error,
                    nextAttemptAt, retryCount, finishedAt) == 1) {
                String attemptStatus = result.status() == WecomSendResult.Status.SENT ? "SUCCEEDED"
                        : result.status().name();
                repository.insertAttempt(new NotificationAttempt(null, delivery.tenantId(), delivery.id(),
                        delivery.notificationId(), delivery.attemptCount(), delivery.channel(), attemptStatus,
                        result.provider(), sanitize(result.providerMessageId(), 255), error,
                        delivery.triggerType(), delivery.triggerActorUserId(), finishedAt));
            }
        });
    }

    long retryDelayMillis(int retryCount) {
        long cappedDelay = properties.retryInitialDelayMs();
        for (int i = 1; i < retryCount && cappedDelay < properties.retryMaxDelayMs(); i++) {
            cappedDelay = cappedDelay > properties.retryMaxDelayMs() / 2
                    ? properties.retryMaxDelayMs() : Math.min(properties.retryMaxDelayMs(), cappedDelay * 2);
        }
        long minimumDelay = cappedDelay - cappedDelay / 2;
        return minimumDelay + ThreadLocalRandom.current().nextLong(cappedDelay - minimumDelay + 1);
    }

    private String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }
}
