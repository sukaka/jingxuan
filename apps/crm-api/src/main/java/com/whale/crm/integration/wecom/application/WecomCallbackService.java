package com.whale.crm.integration.wecom.application;

import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.domain.WecomCallbackEvent;
import com.whale.crm.integration.wecom.infrastructure.mapper.WecomCallbackMapper;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class WecomCallbackService {
    private static final int MAX_ATTEMPTS = 5;
    private static final long LEASE_SECONDS = 60L;
    private static final Set<String> REFRESH = Set.of("add_external_contact", "edit_external_contact");
    private final WecomCallbackMapper mapper;
    private final WecomCallbackCryptoService crypto;
    private final WecomContactSyncService contacts;
    private final WecomOfficialProperties officialProperties;

    public WecomCallbackService(WecomCallbackMapper mapper, WecomCallbackCryptoService crypto,
                                WecomContactSyncService contacts, WecomOfficialProperties officialProperties) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.contacts = contacts;
        this.officialProperties = officialProperties;
    }

    public void accept(String plainXml) {
        long tenantId = officialProperties.allowedTenantId();
        if (tenantId <= 0) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "企微回调租户配置无效");
        }
        String event = crypto.element(plainXml, "Event");
        String change = crypto.element(plainXml, "ChangeType");
        String user = emptyToNull(crypto.element(plainXml, "UserID"));
        String external = emptyToNull(crypto.element(plainXml, "ExternalUserID"));
        long createTime = Long.parseLong(crypto.element(plainXml, "CreateTime"));
        WecomCallbackEvent callback = new WecomCallbackEvent(null, contacts.corpId(tenantId), fingerprint(plainXml), event,
                emptyToNull(change), user, external, Instant.ofEpochSecond(createTime), "RECEIVED", 0);
        mapper.insert(tenantId, Instant.now(), callback);
    }

    @Scheduled(fixedDelayString = "${app.wecom.official.callback-delay-ms:2000}",
            initialDelayString = "${app.wecom.official.callback-initial-delay-ms:${app.wecom.official.callback-delay-ms:2000}}")
    public void processPending() {
        long tenantId = officialProperties.allowedTenantId();
        if (tenantId <= 0) {
            return;
        }
        Instant now = Instant.now();
        mapper.recoverExpiredProcessing(tenantId, now, now.minusSeconds(LEASE_SECONDS), MAX_ATTEMPTS,
                "回调处理租约已过期，已自动回收");
        List<WecomCallbackEvent> pending = mapper.selectPending(tenantId, now, 50);
        for (WecomCallbackEvent event : pending) {
            Instant claimAt = Instant.now();
            if (mapper.claimWithLease(tenantId, event.id(), event.attempts(), claimAt,
                    claimAt.plusSeconds(LEASE_SECONDS)) != 1) {
                continue;
            }
            int attempts = event.attempts() + 1;
            try {
                process(tenantId, event);
                mapper.finishClaimed(tenantId, event.id(), attempts, "SUCCEEDED", null, null, Instant.now());
            } catch (RuntimeException ex) {
                boolean retry = attempts < MAX_ATTEMPTS;
                mapper.finishClaimed(tenantId, event.id(), attempts, retry ? "RETRYABLE" : "FAILED",
                        retry ? Instant.now().plusSeconds(30L * (1 << Math.min(attempts - 1, 5))) : null,
                        "回调处理失败，等待重试或人工检查", retry ? null : Instant.now());
            }
        }
    }

    private void process(long tenantId, WecomCallbackEvent event) {
        if (!"change_external_contact".equals(event.eventType()) || event.changeType() == null) {
            return;
        }
        if (REFRESH.contains(event.changeType())) {
            if (event.externalUserid() == null) {
                throw new IllegalArgumentException("缺少外部联系身份");
            }
            contacts.refreshContact(tenantId, event.externalUserid());
        } else if ("del_external_contact".equals(event.changeType())) {
            contacts.deleteFollow(tenantId, event.wecomUserid(), event.externalUserid(), "DELETED_BY_STAFF");
        } else if ("del_follow_user".equals(event.changeType())) {
            contacts.deleteFollow(tenantId, event.wecomUserid(), event.externalUserid(), "DELETED_BY_CUSTOMER");
        }
    }

    private String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("回调指纹计算失败", ex);
        }
    }

    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
