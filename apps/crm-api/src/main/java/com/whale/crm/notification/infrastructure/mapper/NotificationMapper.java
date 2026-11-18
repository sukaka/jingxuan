package com.whale.crm.notification.infrastructure.mapper;

import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationAttempt;
import com.whale.crm.notification.domain.NotificationDelivery;
import com.whale.crm.notification.domain.WecomMapping;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface NotificationMapper {
    int recipientActive(@Param("tenantId") long tenantId, @Param("recipientUserId") long recipientUserId);

    Notification selectByDedupeKey(@Param("tenantId") long tenantId, @Param("recipientUserId") long recipientUserId,
                                   @Param("dedupeKey") String dedupeKey);

    Notification selectById(@Param("tenantId") long tenantId, @Param("id") long id);

    Notification selectByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    int insertNotification(Notification notification);

    int insertDelivery(@Param("tenantId") long tenantId, @Param("notificationId") long notificationId,
                       @Param("recipientUserId") long recipientUserId, @Param("channel") String channel,
                       @Param("createdAt") Instant createdAt);

    List<Notification> searchOwn(@Param("tenantId") long tenantId, @Param("recipientUserId") long recipientUserId,
                                 @Param("unreadOnly") Boolean unreadOnly, @Param("notificationType") String notificationType,
                                 @Param("offset") int offset, @Param("size") int size);

    long countOwn(@Param("tenantId") long tenantId, @Param("recipientUserId") long recipientUserId,
                  @Param("unreadOnly") Boolean unreadOnly, @Param("notificationType") String notificationType);

    long unreadCount(@Param("tenantId") long tenantId, @Param("recipientUserId") long recipientUserId);

    List<NotificationDelivery> selectOwnDeliveries(@Param("tenantId") long tenantId,
                                                    @Param("recipientUserId") long recipientUserId,
                                                    @Param("notificationIds") List<Long> notificationIds);

    int markRead(@Param("tenantId") long tenantId, @Param("id") long id, @Param("recipientUserId") long recipientUserId,
                 @Param("expectedVersion") long expectedVersion, @Param("readAt") Instant readAt);

    int activateDeliveries(@Param("tenantId") long tenantId, @Param("notificationId") long notificationId);

    List<Long> selectDeliveryTenantIds(@Param("now") Instant now);

    List<NotificationDelivery> selectClaimableDeliveries(@Param("tenantId") long tenantId,
                                                         @Param("now") Instant now, @Param("limit") int limit);

    List<NotificationDelivery> selectExpiredDeliveries(@Param("now") Instant now, @Param("limit") int limit);

    NotificationDelivery selectDeliveryById(@Param("tenantId") long tenantId, @Param("id") long id);

    NotificationDelivery selectDeliveryForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    int claimDelivery(@Param("tenantId") long tenantId, @Param("id") long id,
                      @Param("expectedVersion") long expectedVersion, @Param("owner") String owner,
                      @Param("startedAt") Instant startedAt, @Param("claimExpiresAt") Instant claimExpiresAt);

    int completeDelivery(@Param("tenantId") long tenantId, @Param("id") long id,
                         @Param("expectedVersion") long expectedVersion, @Param("owner") String owner,
                         @Param("status") String status, @Param("provider") String provider,
                         @Param("providerMessageId") String providerMessageId, @Param("errorMasked") String errorMasked,
                         @Param("nextAttemptAt") Instant nextAttemptAt,
                         @Param("automaticRetryCount") int automaticRetryCount, @Param("finishedAt") Instant finishedAt);

    int recoverExpiredDelivery(@Param("tenantId") long tenantId, @Param("id") long id,
                               @Param("expectedVersion") long expectedVersion, @Param("owner") String owner,
                               @Param("errorMasked") String errorMasked, @Param("finishedAt") Instant finishedAt);

    int retryDelivery(@Param("tenantId") long tenantId, @Param("id") long id,
                      @Param("expectedVersion") long expectedVersion, @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("actorUserId") Long actorUserId);

    int insertAttempt(NotificationAttempt attempt);

    List<NotificationDelivery> searchDeliveries(@Param("tenantId") long tenantId,
                                                 @Param("notificationId") Long notificationId,
                                                 @Param("recipientUserId") Long recipientUserId,
                                                 @Param("channel") String channel, @Param("status") String status,
                                                 @Param("offset") int offset, @Param("size") int size);

    long countDeliveries(@Param("tenantId") long tenantId, @Param("notificationId") Long notificationId,
                         @Param("recipientUserId") Long recipientUserId,
                         @Param("channel") String channel, @Param("status") String status);

    List<String> selectWecomUserIds(@Param("tenantId") long tenantId, @Param("recipientUserId") long recipientUserId,
                                    @Param("corpId") String corpId);

    List<WecomMapping> selectWecomMappings(@Param("tenantId") long tenantId);

    WecomMapping selectWecomMapping(@Param("tenantId") long tenantId, @Param("id") long id);

    WecomMapping selectWecomMappingForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    int insertWecomMapping(WecomMapping mapping);

    long lastInsertId();

    int updateWecomMapping(WecomMapping mapping);
}
