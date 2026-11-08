package com.whale.crm.file.infrastructure;

import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.file.domain.FileRepository;
import com.whale.crm.file.infrastructure.mapper.FileMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MybatisFileRepository implements FileRepository {
    private final FileMapper mapper;

    public MybatisFileRepository(FileMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(FileMetadata metadata) {
        mapper.insertFile(metadata.tenantId(), metadata);
    }

    @Override
    public Optional<FileMetadata> findById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectFileById(tenantId, id));
    }

    @Override
    public Optional<FileMetadata> findByObjectKey(long tenantId, String objectKey) {
        return Optional.ofNullable(mapper.selectFileByObjectKey(tenantId, objectKey));
    }

    @Override
    public int startCompletion(long tenantId, long id, long expectedVersion) {
        return mapper.startCompletion(tenantId, id, expectedVersion);
    }

    @Override
    public int updateUploadMetadata(long tenantId, long id, long expectedVersion, String etag,
                                    String sha256, long sizeBytes, String mimeType) {
        return mapper.updateUploadMetadata(tenantId, id, expectedVersion, etag, sha256, sizeBytes, mimeType);
    }

    @Override
    public int publishSealed(long tenantId, long id, long expectedVersion, String expectedStatus, String objectKey) {
        return mapper.publishSealed(tenantId, id, expectedVersion, expectedStatus, objectKey);
    }

    @Override
    public int updateScanStatus(long tenantId, long id, long expectedVersion, String scanStatus, String lastError) {
        return mapper.updateScanStatus(tenantId, id, expectedVersion, scanStatus, lastError);
    }
}
