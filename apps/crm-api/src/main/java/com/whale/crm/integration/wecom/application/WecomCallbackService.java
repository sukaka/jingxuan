package com.whale.crm.integration.wecom.application;

import com.whale.crm.integration.wecom.domain.WecomCallbackEvent;
import com.whale.crm.integration.wecom.infrastructure.mapper.WecomCallbackMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class WecomCallbackService {
    private static final Set<String> REFRESH = Set.of("add_external_contact", "edit_external_contact");
    private final WecomCallbackMapper mapper;
    private final WecomCallbackCryptoService crypto;
    private final WecomContactSyncService contacts;

    public WecomCallbackService(WecomCallbackMapper mapper, WecomCallbackCryptoService crypto,
                                WecomContactSyncService contacts) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.contacts = contacts;
    }

    public void accept(String plainXml) {
        String event = crypto.element(plainXml, "Event");
        String change = crypto.element(plainXml, "ChangeType");
        String user = emptyToNull(crypto.element(plainXml, "UserID"));
        String external = emptyToNull(crypto.element(plainXml, "ExternalUserID"));
        long createTime = Long.parseLong(crypto.element(plainXml, "CreateTime"));
        WecomCallbackEvent callback = new WecomCallbackEvent(null, contacts.corpId(1L), fingerprint(plainXml), event,
                emptyToNull(change), user, external, Instant.ofEpochSecond(createTime), "RECEIVED", 0);
        mapper.insert(1L, Instant.now(), callback);
    }

    @Scheduled(fixedDelayString = "${app.wecom.official.callback-delay-ms:2000}")
    public void processPending() {
        List<WecomCallbackEvent> pending = mapper.selectPending(1L, Instant.now(), 50);
        for (WecomCallbackEvent event : pending) {
            if (mapper.claim(1L, event.id(), event.attempts(), Instant.now()) != 1) {
                continue;
            }
            try {
                process(event);
                mapper.finish(1L, event.id(), "SUCCEEDED", null, null, Instant.now());
            } catch (RuntimeException ex) {
                int attempts = event.attempts() + 1;
                boolean retry = attempts < 5;
                mapper.finish(1L, event.id(), retry ? "RETRYABLE" : "FAILED",
                        retry ? Instant.now().plusSeconds(30L * (1L << Math.min(attempts - 1, 5))) : null,
                        "回调处理失败，等待重试或人工检查", retry ? null : Instant.now());
            }
        }
    }

    private void process(WecomCallbackEvent event) {
        if (!"change_external_contact".equals(event.eventType()) || event.changeType() == null) {
            return;
        }
        if (REFRESH.contains(event.changeType())) {
            if (event.externalUserid() == null) {
                throw new IllegalArgumentException("缺少外部联系身份");
            }
            contacts.refreshContact(1L, event.externalUserid());
        } else if ("del_external_contact".equals(event.changeType())) {
            contacts.deleteFollow(1L, event.wecomUserid(), event.externalUserid(), "DELETED_BY_STAFF");
        } else if ("del_follow_user".equals(event.changeType())) {
            contacts.deleteFollow(1L, event.wecomUserid(), event.externalUserid(), "DELETED_BY_CUSTOMER");
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
