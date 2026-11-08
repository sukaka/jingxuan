package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomContact(Long id, String corpId, String externalUserid, Long staffUserId,
                           String name, String avatar, Integer contactType, Integer gender, String unionid,
                           String remark, String description, Integer addWay, String channelState,
                           Instant followCreatedAt, String tagSnapshotJson, String followStatus, Long customerId,
                           String consentStatus, String consentEvidenceRef, String linkType, String linkReason,
                           Long createdBy, Instant lastSyncedAt, Long version) {
}
