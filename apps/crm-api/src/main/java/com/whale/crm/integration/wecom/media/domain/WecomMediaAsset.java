package com.whale.crm.integration.wecom.media.domain;

import java.time.Instant;

public record WecomMediaAsset(Long id, long tenantId, long messageId, int assetIndex, String sdkFileId,
                              String mediaKind, String md5sum, Long fileSize, String fileName, String contentType,
                              String status, String objectKey, String contentSha256, Long actualSize,
                              String errorMasked, int attemptCount, Instant nextRetryAt, String leaseOwner,
                              Instant leaseUntil, Instant createdAt, Instant updatedAt, long version) {
}
