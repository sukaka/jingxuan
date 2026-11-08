package com.whale.crm.file.infrastructure.mapper;

import com.whale.crm.file.domain.FileMetadata;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileMapper {
    void insertFile(@Param("tenantId") long tenantId, @Param("entity") FileMetadata metadata);

    FileMetadata selectFileById(@Param("tenantId") long tenantId, @Param("id") long id);

    FileMetadata selectFileByObjectKey(@Param("tenantId") long tenantId, @Param("objectKey") String objectKey);

    int startCompletion(@Param("tenantId") long tenantId, @Param("id") long id,
                        @Param("expectedVersion") long expectedVersion);

    int updateUploadMetadata(@Param("tenantId") long tenantId, @Param("id") long id,
                             @Param("expectedVersion") long expectedVersion, @Param("etag") String etag,
                             @Param("sha256") String sha256, @Param("sizeBytes") long sizeBytes,
                             @Param("mimeType") String mimeType);

    int publishSealed(@Param("tenantId") long tenantId, @Param("id") long id,
                      @Param("expectedVersion") long expectedVersion, @Param("expectedStatus") String expectedStatus,
                      @Param("objectKey") String objectKey);

    int updateScanStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                         @Param("expectedVersion") long expectedVersion,
                         @Param("scanStatus") String scanStatus, @Param("lastError") String lastError);
}
