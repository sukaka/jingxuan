package com.whale.crm.notification.infrastructure.wecom;

import com.whale.crm.notification.domain.NotificationRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WecomMemberMappingResolver {
    private final NotificationRepository repository;
    private final WecomMessageProperties properties;

    public WecomMemberMappingResolver(NotificationRepository repository, WecomMessageProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public String resolve(long tenantId, long recipientUserId) {
        List<String> userIds = repository.findWecomUserIds(tenantId, recipientUserId, properties.corpId())
                .stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (userIds.isEmpty()) {
            throw new WecomMemberMappingException("WECOM_MEMBER_MAPPING_MISSING");
        }
        if (userIds.size() != 1) {
            throw new WecomMemberMappingException("WECOM_MEMBER_MAPPING_AMBIGUOUS");
        }
        return userIds.getFirst();
    }
}
