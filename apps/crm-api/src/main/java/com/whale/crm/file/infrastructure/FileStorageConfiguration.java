package com.whale.crm.file.infrastructure;

import com.whale.crm.file.application.ClamAvScanner;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FileProperties.class)
public class FileStorageConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.files", name = "enabled", havingValue = "true")
    public MinioClient minioClient(FileProperties properties) {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey());
        if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
            builder.region(properties.getRegion());
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.files", name = "enabled", havingValue = "true")
    public PrivateObjectStorage privateObjectStorage(MinioClient client, FileProperties properties) {
        return new MinioPrivateObjectStorage(client, properties);
    }

    @Bean
    public ClamAvScanner clamAvScanner(FileProperties properties) {
        return new ClamAvScanner(properties.getClamavHost(), properties.getClamavPort(),
                properties.getClamavConnectTimeoutMs(), properties.getClamavReadTimeoutMs());
    }
}
