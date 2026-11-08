package com.whale.crm.file.domain;

import java.util.Optional;

public interface FileRepository {
    void insert(FileMetadata metadata);

    Optional<FileMetadata> findById(long tenantId, long id);

    Optional<FileMetadata> findByObjectKey(long tenantId, String objectKey);

    int startCompletion(long tenantId, long id, long expectedVersion);

    int updateUploadMetadata(long tenantId, long id, long expectedVersion, String etag,
                             String sha256, long sizeBytes, String mimeType);

    int publishSealed(long tenantId, long id, long expectedVersion, String expectedStatus, String objectKey);

    int updateScanStatus(long tenantId, long id, long expectedVersion, String scanStatus, String lastError);
}
