package com.whale.crm.file;

import com.whale.crm.file.infrastructure.FileProperties;
import com.whale.crm.file.infrastructure.MinioPrivateObjectStorage;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MinioPrivateObjectStorageTest {
    @Test
    void presignGetUsesBrowserReachableEndpointWithoutCallingInternalStorage() {
        FileProperties properties = properties();
        properties.setPublicEndpoint("https://files.example.test");
        MinioPrivateObjectStorage storage = storage(properties);

        String url = storage.presignGet("tenant-1/guide.txt", 120);

        assertThat(url).startsWith("https://files.example.test/");
        assertThat(url).contains("crm-private-test").contains("X-Amz-Signature=");
    }

    @Test
    void presignGetRetainsInternalEndpointWhenPublicEndpointIsNotConfigured() {
        FileProperties properties = properties();
        MinioPrivateObjectStorage storage = storage(properties);

        String url = storage.presignGet("tenant-1/guide.txt", 120);

        assertThat(url).startsWith("http://127.0.0.1:9000/");
        assertThat(url).contains("X-Amz-Signature=");
    }

    private MinioPrivateObjectStorage storage(FileProperties properties) {
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .region(properties.getRegion())
                .build();
        return new MinioPrivateObjectStorage(client, properties);
    }

    private FileProperties properties() {
        FileProperties properties = new FileProperties();
        properties.setEndpoint("http://127.0.0.1:9000");
        properties.setAccessKey("synthetic-test-key");
        properties.setSecretKey("synthetic-test-secret");
        properties.setBucket("crm-private-test");
        properties.setRegion("us-east-1");
        return properties;
    }
}
