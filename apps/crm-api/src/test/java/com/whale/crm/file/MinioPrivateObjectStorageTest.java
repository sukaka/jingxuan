package com.whale.crm.file;

import com.whale.crm.file.infrastructure.FileProperties;
import com.whale.crm.file.infrastructure.MinioPrivateObjectStorage;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

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

    @Test
    void presignPutUsesBrowserReachableEndpointAndSignsWithoutContentLengthHeader() throws Exception {
        FileProperties properties = properties();
        properties.setPublicEndpoint("https://files.example.test");
        MinioPrivateObjectStorage storage = storageWithExistingBucket(properties);

        String url = storage.presignPut("contract/1/upload.pdf", 300);

        assertThat(url).startsWith("https://files.example.test/");
        assertThat(url).contains("crm-private-test")
                .contains("X-Amz-Expires=300")
                .contains("X-Amz-Signature=")
                .doesNotContain("content-length");
    }

    @Test
    void presignPutRetainsInternalEndpointWhenPublicEndpointIsNotConfigured() throws Exception {
        FileProperties properties = properties();
        MinioPrivateObjectStorage storage = storageWithExistingBucket(properties);

        String url = storage.presignPut("contract/1/upload.pdf", 300);

        assertThat(url).startsWith("http://127.0.0.1:9000/");
        assertThat(url).contains("X-Amz-Expires=300").contains("X-Amz-Signature=");
    }

    private MinioPrivateObjectStorage storage(FileProperties properties) {
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .region(properties.getRegion())
                .build();
        return new MinioPrivateObjectStorage(client, properties);
    }

    private MinioPrivateObjectStorage storageWithExistingBucket(FileProperties properties) throws Exception {
        MinioClient client = Mockito.spy(MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .region(properties.getRegion())
                .build());
        doReturn(true).when(client).bucketExists(any(BucketExistsArgs.class));
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
