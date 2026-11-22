package com.whale.crm.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository {
    boolean recipientActive(long tenantId, long recipientUserId);

    boolean employeeActive(long tenantId, long staffUserId);

    Optional<Notification> findByDedupeKey(long tenantId, long recipientUserId, String dedupeKey);

    Optional<Notification> findById(long tenantId, long id);

    Optional<Notification> findByIdForUpdate(long tenantId, long id);

    int insertNotification(Notification notification);

    void insertDelivery(long tenantId, long notificationId, long recipientUserId,
                        NotificationChannel channel, Instant createdAt);

    List<Notification> searchOwn(long tenantId, long recipientUserId, Boolean unreadOnly,
                                 String notificationType, int offset, int size);

    long countOwn(long tenantId, long recipientUserId, Boolean unreadOnly, String notificationType);

    long unreadCount(long tenantId, long recipientUserId);

    List<NotificationDelivery> findOwnDeliveries(long tenantId, long recipientUserId, List<Long> notificationIds);

    int markRead(long tenantId, long id, long recipientUserId, long expectedVersion, Instant readAt);

    int activateDeliveries(long tenantId, long notificationId);

    List<Long> findDeliveryTenantIds(Instant now);

    List<NotificationDelivery> findClaimableDeliveries(long tenantId, Instant now, int limit);

    List<NotificationDelivery> findExpiredDeliveries(Instant now, int limit);

    Optional<NotificationDelivery> findDeliveryById(long tenantId, long id);

    Optional<NotificationDelivery> findDeliveryForUpdate(long tenantId, long id);

    int claimDelivery(long tenantId, long id, long expectedVersion, String owner,
                      Instant startedAt, Instant claimExpiresAt);

    int completeDelivery(long tenantId, long id, long expectedVersion, String owner, String status,
                         String provider, String providerMessageId, String errorMasked,
                         Instant nextAttemptAt, int automaticRetryCount, Instant finishedAt);

    int recoverExpiredDelivery(long tenantId, long id, long expectedVersion, String owner,
                               String errorMasked, Instant finishedAt);

    int retryDelivery(long tenantId, long id, long expectedVersion, Instant nextAttemptAt, Long actorUserId);

    void insertAttempt(NotificationAttempt attempt);

    List<NotificationDelivery> searchDeliveries(long tenantId, Long notificationId, Long recipientUserId,
                                                 String channel, String status, int offset, int size);

    long countDeliveries(long tenantId, Long notificationId, Long recipientUserId,
                         String channel, String status);

    List<String> findWecomUserIds(long tenantId, long recipientUserId, String corpId);

    List<WecomMapping> findWecomMappings(long tenantId);

    Optional<WecomMapping> findWecomMapping(long tenantId, long id);

    Optional<WecomMapping> findWecomMappingForUpdate(long tenantId, long id);

    long insertWecomMapping(WecomMapping mapping);

    int updateWecomMapping(WecomMapping mapping);
}
