package com.whale.crm.notification.application;

import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.notification.domain.NotificationRequest;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.application.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class NotificationRequestService {
    public static final String EVENT_TYPE = "notification.requested.v1";
    public static final String AGGREGATE_TYPE = "notification";

    private final NotificationRepository repository;
    private final OutboxService outboxService;

    public NotificationRequestService(NotificationRepository repository, OutboxService outboxService) {
        this.repository = repository;
        this.outboxService = outboxService;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Notification request(NotificationRequest input) {
        NotificationRequest request = normalize(input);
        Optional<Notification> existing = repository.findByDedupeKey(
                request.tenantId(), request.recipientUserId(), request.dedupeKey());
        if (existing.isPresent()) {
            return requireSameRequest(request, existing.orElseThrow());
        }
        if (!repository.recipientActive(request.tenantId(), request.recipientUserId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "通知接收人不存在或已停用");
        }
        Instant now = Instant.now();
        Notification candidate = new Notification(null, request.tenantId(), request.recipientUserId(),
                request.notificationType(), request.title(), request.contentMasked(), request.sourceType(),
                request.sourceId(), request.dedupeKey(), request.deepLink(), channelNames(request), now, null, 0L);
        repository.insertNotification(candidate);
        Notification stored = repository.findByDedupeKey(request.tenantId(), request.recipientUserId(),
                request.dedupeKey()).orElseThrow(() -> new IllegalStateException("通知写入后不可读取"));
        requireSameRequest(request, stored);
        for (NotificationChannel channel : NotificationChannel.values()) {
            if (request.channels().contains(channel)) {
                repository.insertDelivery(stored.tenantId(), stored.id(), stored.recipientUserId(), channel, now);
            }
        }
        outboxService.append(stored.tenantId(), EVENT_TYPE + ":" + stored.id(), EVENT_TYPE, AGGREGATE_TYPE,
                Long.toString(stored.id()), Map.of("notificationId", stored.id()));
        return stored;
    }

    private Notification requireSameRequest(NotificationRequest request, Notification stored) {
        boolean same = stored.tenantId() == request.tenantId()
                && stored.recipientUserId() == request.recipientUserId()
                && Objects.equals(stored.notificationType(), request.notificationType())
                && Objects.equals(stored.title(), request.title())
                && Objects.equals(stored.contentMasked(), request.contentMasked())
                && Objects.equals(stored.sourceType(), request.sourceType())
                && Objects.equals(stored.sourceId(), request.sourceId())
                && Objects.equals(stored.dedupeKey(), request.dedupeKey())
                && Objects.equals(stored.deepLink(), request.deepLink())
                && Objects.equals(stored.channels(), channelNames(request));
        if (!same) {
            throw new BusinessException(ErrorCode.CONFLICT, "通知去重键已用于不同的逻辑通知");
        }
        return stored;
    }

    private NotificationRequest normalize(NotificationRequest request) {
        if (request == null || request.tenantId() <= 0 || request.recipientUserId() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "通知租户与接收人必须有效");
        }
        if (request.channels() == null || request.channels().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "通知至少需要一个投递渠道");
        }
        return new NotificationRequest(request.tenantId(), request.recipientUserId(),
                required(request.notificationType(), "通知类型", 64), required(request.title(), "通知标题", 200),
                required(request.contentMasked(), "脱敏正文", 4000), required(request.sourceType(), "来源类型", 64),
                required(request.sourceId(), "来源ID", 128), required(request.dedupeKey(), "去重键", 128),
                deepLink(request.deepLink()), request.channels());
    }

    private String required(String value, String label, int maxLength) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能为空且长度不能超过" + maxLength);
        }
        return value.strip();
    }

    private String deepLink(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String link = value.strip();
        if (link.length() > 1024 || !link.startsWith("/") || link.startsWith("//") || link.contains("\\")
                || link.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "通知跳转地址必须是安全的站内相对路径");
        }
        return link;
    }

    private String channelNames(NotificationRequest request) {
        return request.channels().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}
