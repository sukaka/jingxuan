package com.whale.crm.file.api.dto;

import com.whale.crm.file.domain.FileMetadata;

import java.time.Instant;

public record FileMetadataResponse(Long id, String objectKey, String originalName, String mimeType,
                                   Long sizeBytes, String sha256, String classification, Long institutionId,
                                   String scanStatus, Long uploadedBy, String etag, String lastError,
                                   Instant expiresAt, Instant createdAt, Instant updatedAt, Long version) {
    public static FileMetadataResponse from(FileMetadata metadata) {
        return new FileMetadataResponse(metadata.id(), metadata.objectKey(), metadata.originalName(), metadata.mimeType(),
                metadata.sizeBytes(), metadata.sha256(), metadata.classification(), metadata.institutionId(),
                metadata.scanStatus(), metadata.uploadedBy(), metadata.etag(), metadata.lastError(),
                metadata.expiresAt(), metadata.createdAt(), metadata.updatedAt(), metadata.version());
    }
}
