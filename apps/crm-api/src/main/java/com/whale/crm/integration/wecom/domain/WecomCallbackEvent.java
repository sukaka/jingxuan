package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomCallbackEvent(Long id, String corpId, String eventFingerprint, String eventType,
                                 String changeType, String wecomUserid, String externalUserid,
                                 Instant eventTime, String status, int attempts) {
}
