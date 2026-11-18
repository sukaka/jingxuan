package com.whale.crm.notification.infrastructure;

import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationAttempt;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationDelivery;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.notification.infrastructure.mapper.NotificationMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisNotificationRepository implements NotificationRepository {
    private final NotificationMapper mapper;

    public MybatisNotificationRepository(NotificationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean recipientActive(long tenantId, long recipientUserId) {
        return mapper.recipientActive(tenantId, recipientUserId) > 0;
    }

    @Override
    public Optional<Notification> findByDedupeKey(long tenantId, long recipientUserId, String dedupeKey) {
        return Optional.ofNullable(mapper.selectByDedupeKey(tenantId, recipientUserId, dedupeKey));
    }

    @Override
    public Optional<Notification> findById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectById(tenantId, id));
    }

    @Override
    public Optional<Notification> findByIdForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectByIdForUpdate(tenantId, id));
    }

    @Override
    public int insertNotification(Notification notification) {
        return mapper.insertNotification(notification);
    }

    @Override
    public void insertDelivery(long tenantId, long notificationId, long recipientUserId,
                               NotificationChannel channel, Instant createdAt) {
        mapper.insertDelivery(tenantId, notificationId, recipientUserId, channel.name(), createdAt);
    }

    @Override
    public List<Notification> searchOwn(long tenantId, long recipientUserId, Boolean unreadOnly,
                                        String notificationType, int offset, int size) {
        return mapper.searchOwn(tenantId, recipientUserId, unreadOnly, notificationType, offset, size);
    }

    @Override
    public long countOwn(long tenantId, long recipientUserId, Boolean unreadOnly, String notificationType) {
        return mapper.countOwn(tenantId, recipientUserId, unreadOnly, notificationType);
    }

    @Override
    public long unreadCount(long tenantId, long recipientUserId) {
        return mapper.unreadCount(tenantId, recipientUserId);
    }

    @Override
    public List<NotificationDelivery> findOwnDeliveries(long tenantId, long recipientUserId, List<Long> notificationIds) {
        if (notificationIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectOwnDeliveries(tenantId, recipientUserId, notificationIds);
    }

    @Override
    public int markRead(long tenantId, long id, long recipientUserId, long expectedVersion, Instant readAt) {
        return mapper.markRead(tenantId, id, recipientUserId, expectedVersion, readAt);
    }

    @Override
    public int activateDeliveries(long tenantId, long notificationId) {
        return mapper.activateDeliveries(tenantId, notificationId);
    }

    @Override
    public List<Long> findDeliveryTenantIds(Instant now) {
        return mapper.selectDeliveryTenantIds(now);
    }

    @Override
    public List<NotificationDelivery> findClaimableDeliveries(long tenantId, Instant now, int limit) {
        return mapper.selectClaimableDeliveries(tenantId, now, limit);
    }

    @Override
    public List<NotificationDelivery> findExpiredDeliveries(Instant now, int limit) {
        return mapper.selectExpiredDeliveries(now, limit);
    }

    @Override
    public Optional<NotificationDelivery> findDeliveryById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectDeliveryById(tenantId, id));
    }

    @Override
    public Optional<NotificationDelivery> findDeliveryForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectDeliveryForUpdate(tenantId, id));
    }

    @Override
    public int claimDelivery(long tenantId, long id, long expectedVersion, String owner,
                             Instant startedAt, Instant claimExpiresAt) {
        return mapper.claimDelivery(tenantId, id, expectedVersion, owner, startedAt, claimExpiresAt);
    }

    @Override
    public int completeDelivery(long tenantId, long id, long expectedVersion, String owner, String status,
                                String provider, String providerMessageId, String errorMasked,
                                Instant nextAttemptAt, int automaticRetryCount, Instant finishedAt) {
        return mapper.completeDelivery(tenantId, id, expectedVersion, owner, status, provider, providerMessageId,
                errorMasked, nextAttemptAt, automaticRetryCount, finishedAt);
    }

    @Override
    public int recoverExpiredDelivery(long tenantId, long id, long expectedVersion, String owner,
                                      String errorMasked, Instant finishedAt) {
        return mapper.recoverExpiredDelivery(tenantId, id, expectedVersion, owner, errorMasked, finishedAt);
    }

    @Override
    public int retryDelivery(long tenantId, long id, long expectedVersion, Instant nextAttemptAt, Long actorUserId) {
        return mapper.retryDelivery(tenantId, id, expectedVersion, nextAttemptAt, actorUserId);
    }

    @Override
    public void insertAttempt(NotificationAttempt attempt) {
        mapper.insertAttempt(attempt);
    }

    @Override
    public List<NotificationDelivery> searchDeliveries(long tenantId, Long notificationId, Long recipientUserId,
                                                        String channel, String status, int offset, int size) {
        return mapper.searchDeliveries(tenantId, notificationId, recipientUserId, channel, status, offset, size);
    }

    @Override
    public long countDeliveries(long tenantId, Long notificationId, Long recipientUserId,
                                String channel, String status) {
        return mapper.countDeliveries(tenantId, notificationId, recipientUserId, channel, status);
    }

    @Override
    public List<String> findWecomUserIds(long tenantId, long recipientUserId, String corpId) {
        return mapper.selectWecomUserIds(tenantId, recipientUserId, corpId);
    }

    @Override
    public List<com.whale.crm.notification.domain.WecomMapping> findWecomMappings(long tenantId) {
        return mapper.selectWecomMappings(tenantId);
    }

    @Override
    public Optional<com.whale.crm.notification.domain.WecomMapping> findWecomMapping(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectWecomMapping(tenantId, id));
    }

    @Override
    public Optional<com.whale.crm.notification.domain.WecomMapping> findWecomMappingForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectWecomMappingForUpdate(tenantId, id));
    }

    @Override
    public long insertWecomMapping(com.whale.crm.notification.domain.WecomMapping mapping) {
        mapper.insertWecomMapping(mapping);
        return mapper.lastInsertId();
    }

    @Override
    public int updateWecomMapping(com.whale.crm.notification.domain.WecomMapping mapping) {
        return mapper.updateWecomMapping(mapping);
    }
}
