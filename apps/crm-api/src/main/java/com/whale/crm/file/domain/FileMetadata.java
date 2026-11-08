package com.whale.crm.file.domain;

import java.time.Instant;

public record FileMetadata(Long id, Long tenantId, String objectKey, String originalName, String mimeType,
                           Long sizeBytes, String sha256, String classification, Long institutionId,
                           String scanStatus, Long uploadedBy, String etag, String lastError,
                           Instant expiresAt, Instant createdAt, Instant updatedAt, Long version) {
}
