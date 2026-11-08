package com.whale.crm.file.infrastructure;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class MinioPrivateObjectStorage implements PrivateObjectStorage {
    private final MinioClient client;
    private final MinioClient signingClient;
    private final FileProperties properties;

    public MinioPrivateObjectStorage(MinioClient client, FileProperties properties) {
        this.client = client;
        String publicEndpoint = properties.getPublicEndpoint();
        this.signingClient = publicEndpoint == null || publicEndpoint.isBlank() ? client : createSigningClient(properties);
        this.properties = properties;
    }

    private MinioClient createSigningClient(FileProperties properties) {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(properties.getPublicEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey());
        if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
            builder.region(properties.getRegion());
        }
        return builder.build();
    }

    @Override
    public void putObject(String objectKey, byte[] content, String contentType) {
        putObject(objectKey, new ByteArrayInputStream(content), content.length, contentType);
    }

    @Override
    public void putObject(String objectKey, InputStream content, long sizeBytes, String contentType) {
        ensureBucket();
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(content, sizeBytes, -1L)
                    .contentType(contentType)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Object storage write failed", ex);
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Object storage delete failed", ex);
        }
    }

    @Override
    public String presignPut(String objectKey, int expirySeconds) {
        ensureBucket();
        try {
            return signingClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.PUT)
                    .expiry(expirySeconds)
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Object storage presign failed");
        }
    }

    @Override
    public ObjectStat stat(String objectKey) {
        try {
            StatObjectResponse response = client.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
            return new ObjectStat(response.etag(), response.size(), response.contentType());
        } catch (Exception ex) {
            throw new IllegalStateException("Object storage stat failed");
        }
    }

    @Override
    public InputStream getObject(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Object storage read failed");
        }
    }

    @Override
    public String presignGet(String objectKey, int expirySeconds) {
        try {
            return signingClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.GET)
                    .expiry(expirySeconds)
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Object storage download presign failed");
        }
    }

    private void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Object storage bucket initialization failed");
        }
    }
}
