package com.whale.crm.file.infrastructure;

import java.io.InputStream;
import java.time.Instant;

public interface PrivateObjectStorage {
    void putObject(String objectKey, byte[] content, String contentType);

    void putObject(String objectKey, InputStream content, long sizeBytes, String contentType);

    void deleteObject(String objectKey);

    String presignPut(String objectKey, int expirySeconds);

    ObjectStat stat(String objectKey);

    InputStream getObject(String objectKey);

    String presignGet(String objectKey, int expirySeconds);

    record ObjectStat(String etag, long sizeBytes, String contentType) {
    }

    record PresignedUrl(String url, Instant expiresAt) {
    }
}
