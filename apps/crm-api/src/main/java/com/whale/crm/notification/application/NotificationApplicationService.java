package com.whale.crm.notification.application;

import com.whale.crm.notification.api.dto.MarkNotificationReadRequest;
import com.whale.crm.notification.api.dto.NotificationDeliveryResponse;
import com.whale.crm.notification.api.dto.NotificationResponse;
import com.whale.crm.notification.api.dto.RetryNotificationDeliveryRequest;
import com.whale.crm.notification.api.dto.UnreadCountResponse;
import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationDelivery;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
public class NotificationApplicationService {
    private static final String READ_SCOPE = "notification.read";
    private static final String RETRY_SCOPE = "notification.delivery.retry";

    private final NotificationRepository repository;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;

    public NotificationApplicationService(NotificationRepository repository, IdempotencyService idempotencyService,
                                          AuditService auditService) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> searchOwn(long tenantId, CurrentUser user, Boolean unreadOnly,
                                                        String notificationType, int page, int size) {
        requireAccess(tenantId, user, "notification:read");
        int offset = (page - 1) * size;
        java.util.List<Notification> notifications = repository.searchOwn(
                tenantId, user.userId(), unreadOnly, notificationType, offset, size);
        java.util.List<Long> ids = notifications.stream().map(Notification::id).toList();
        Map<Long, java.util.List<NotificationDelivery>> deliveries = repository.findOwnDeliveries(
                tenantId, user.userId(), ids).stream().collect(java.util.stream.Collectors.groupingBy(
                        NotificationDelivery::notificationId));
        return new PageResponse<>(notifications.stream().map(notification -> NotificationResponse.from(notification,
                        deliveries.getOrDefault(notification.id(), java.util.List.of()))).toList(),
                repository.countOwn(tenantId, user.userId(), unreadOnly, notificationType), page, size);
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(long tenantId, CurrentUser user) {
        requireAccess(tenantId, user, "notification:read");
        return new UnreadCountResponse(repository.unreadCount(tenantId, user.userId()));
    }

    @Transactional
    public NotificationResponse markRead(long tenantId, CurrentUser user, long id,
                                         MarkNotificationReadRequest request, String idempotencyKey) {
        requireAccess(tenantId, user, "notification:read");
        String payload = id + ":" + (request == null ? "CURRENT" : request.version());
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, READ_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return response(tenantId, user.userId(), requireOwn(tenantId, user, id));
        }
        Notification before = repository.findByIdForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "通知不存在或不可见"));
        requireOwner(user, before);
        long expectedVersion = request == null ? before.version() : request.version();
        if (before.version().longValue() != expectedVersion) {
            throw new BusinessException(ErrorCode.CONFLICT, "通知已被他人修改，请刷新后重试");
        }
        if (before.readAt() != null) {
            idempotencyService.recordSuccess(tenantId, READ_SCOPE, idempotencyKey, "NOTIFICATION",
                    Long.toString(id), payload);
            return response(tenantId, user.userId(), before);
        }
        Instant now = Instant.now();
        if (repository.markRead(tenantId, id, user.userId(), expectedVersion, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "通知状态已变化，请刷新后重试");
        }
        Notification after = requireOwn(tenantId, user, id);
        auditService.record(tenantId, user.userId(), "NOTIFICATION_READ", "NOTIFICATION", Long.toString(id),
                snapshot(before), snapshot(after), null);
        idempotencyService.recordSuccess(tenantId, READ_SCOPE, idempotencyKey, "NOTIFICATION",
                Long.toString(id), payload);
        return response(tenantId, user.userId(), after);
    }

    private NotificationResponse response(long tenantId, long userId, Notification notification) {
        return NotificationResponse.from(notification, repository.findOwnDeliveries(
                tenantId, userId, java.util.List.of(notification.id())));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDeliveryResponse> searchDeliveries(long tenantId, CurrentUser user,
                                                                       Long notificationId, Long recipientUserId,
                                                                       String channel, String status, int page, int size) {
        requireAccess(tenantId, user, "notification:delivery:read");
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchDeliveries(tenantId, notificationId, recipientUserId,
                channel, status, offset, size).stream().map(NotificationDeliveryResponse::from).toList(),
                repository.countDeliveries(tenantId, notificationId, recipientUserId, channel, status), page, size);
    }

    @Transactional
    public NotificationDeliveryResponse retry(long tenantId, CurrentUser user, long id,
                                              RetryNotificationDeliveryRequest request, String idempotencyKey) {
        requireAccess(tenantId, user, "notification:delivery:retry");
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();
        String payload = id + ":" + request.version() + ":" + (reason == null ? "" : reason);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, RETRY_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return NotificationDeliveryResponse.from(requireDelivery(tenantId, id));
        }
        NotificationDelivery before = repository.findDeliveryForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "投递记录不存在或不可见"));
        if (before.version().longValue() != request.version().longValue()) {
            throw new BusinessException(ErrorCode.CONFLICT, "投递记录已被他人修改，请刷新后重试");
        }
        if (!java.util.Set.of("FAILED", "UNKNOWN", "DISABLED").contains(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有失败、结果未知或已禁用的投递可以手工重试");
        }
        if ("UNKNOWN".equals(before.status()) && reason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结果未知的投递重试前必须填写人工核实原因");
        }
        Instant now = Instant.now();
        if (repository.retryDelivery(tenantId, id, request.version(), now, user.userId()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "投递状态已变化，请刷新后重试");
        }
        NotificationDelivery after = requireDelivery(tenantId, id);
        auditService.record(tenantId, user.userId(), "NOTIFICATION_DELIVERY_RETRY", "NOTIFICATION_DELIVERY",
                Long.toString(id), snapshot(before), snapshot(after), reason);
        idempotencyService.recordSuccess(tenantId, RETRY_SCOPE, idempotencyKey, "NOTIFICATION_DELIVERY",
                Long.toString(id), payload);
        return NotificationDeliveryResponse.from(after);
    }

    private Notification requireOwn(long tenantId, CurrentUser user, long id) {
        Notification notification = repository.findById(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "通知不存在或不可见"));
        requireOwner(user, notification);
        return notification;
    }

    private void requireOwner(CurrentUser user, Notification notification) {
        if (notification.recipientUserId() != user.userId()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "通知不存在或不可见");
        }
    }

    private NotificationDelivery requireDelivery(long tenantId, long id) {
        return repository.findDeliveryById(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "投递记录不存在或不可见"));
    }

    private void requireResource(IdempotencyRecord record, long id) {
        if (!Long.toString(id).equals(record.getResourceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果与请求资源不一致");
        }
    }

    private void requireAccess(long tenantId, CurrentUser user, String permission) {
        if (tenantId != user.tenantId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "跨租户访问被拒绝");
        }
        if (!user.permissions().contains(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有访问该资源的权限");
        }
    }

    private Map<String, Object> snapshot(Notification notification) {
        return Map.of("readAt", notification.readAt() == null ? "" : notification.readAt().toString(),
                "version", notification.version());
    }

    private Map<String, Object> snapshot(NotificationDelivery delivery) {
        return Map.of("status", delivery.status(), "version", delivery.version());
    }
}
