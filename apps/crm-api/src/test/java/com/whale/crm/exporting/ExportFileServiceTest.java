package com.whale.crm.exporting;

import com.whale.crm.exporting.domain.ExportJob;
import com.whale.crm.exporting.infrastructure.ExportFileService;
import com.whale.crm.exporting.infrastructure.ExportProperties;
import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.application.FileContentInspector;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.file.domain.FileRepository;
import com.whale.crm.file.infrastructure.FileProperties;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportFileServiceTest {
    @Mock FileRepository repository;
    @Mock ObjectProvider<PrivateObjectStorage> storageProvider;
    @Mock PrivateObjectStorage storage;
    @Mock FileContentInspector inspector;
    @Mock ClamAvScanner scanner;

    @Test
    void nonCleanObjectIsDeletedAndNeverRegistered() throws Exception {
        Path csv = Files.createTempFile("export-scan-test-", ".csv");
        try {
            Files.writeString(csv, "客户编号,备注\r\nC-1,synthetic\r\n");
            ExportFileService service = exportFileService();
            when(storageProvider.getIfAvailable()).thenReturn(storage);
            when(inspector.inspect(csv)).thenReturn(new FileContentInspector.Inspection(
                    Files.size(csv), "a".repeat(64), "b".repeat(32), "text/csv"));
            when(repository.findByObjectKey(anyLong(), anyString())).thenReturn(Optional.empty());
            when(storage.stat(anyString())).thenReturn(new PrivateObjectStorage.ObjectStat(
                    "b".repeat(32), Files.size(csv), "text/csv"));
            when(scanner.scan(csv)).thenReturn(ClamAvScanner.ScanResult.FOUND);
            ExportJob job = job("EXP-SCAN");

            assertThatThrownBy(() -> service.registerCleanFile(job, csv, Instant.now().plusSeconds(60)))
                    .isInstanceOf(BusinessException.class);

            verify(storage).deleteObject("export/1/EXP-SCAN.csv");
            verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
            verify(repository, never()).insert(any(FileMetadata.class));
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    @Test
    void leaseRecoveryWithMatchingReusableExistingSkipsUpload() throws Exception {
        Path csv = Files.createTempFile("export-reuse-test-", ".csv");
        try {
            Files.writeString(csv, "客户编号,备注\r\nC-1,synthetic\r\n");
            long size = Files.size(csv);
            ExportFileService service = exportFileService();
            when(storageProvider.getIfAvailable()).thenReturn(storage);
            when(inspector.inspect(csv)).thenReturn(new FileContentInspector.Inspection(
                    size, "a".repeat(64), "b".repeat(32), "text/csv"));
            Instant future = Instant.now().plusSeconds(600);
            FileMetadata existing = metadata(101L, "export/1/EXP-REUSE.csv", "a".repeat(64), size, "CLEAN", 4L, future);
            when(repository.findByObjectKey(1L, "export/1/EXP-REUSE.csv")).thenReturn(Optional.of(existing));
            when(scanner.scan(csv)).thenReturn(ClamAvScanner.ScanResult.CLEAN);
            when(storage.stat("export/1/EXP-REUSE.csv")).thenReturn(new PrivateObjectStorage.ObjectStat(
                    "b".repeat(32), size, "text/csv"));

            FileMetadata result = service.registerCleanFile(job("EXP-REUSE"), csv, future);

            assertThat(result).isSameAs(existing);
            verify(storage, never()).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
            verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
            verify(repository, never()).insert(any(FileMetadata.class));
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    @Test
    void leaseRecoveryWithChangedShaOrExpiredExistingAllocatesNewUniqueObjectKey() throws Exception {
        Path csv = Files.createTempFile("export-recover-test-", ".csv");
        try {
            Files.writeString(csv, "客户编号,备注\r\nC-2,changed\r\n");
            long size = Files.size(csv);
            ExportFileService service = exportFileService();
            when(storageProvider.getIfAvailable()).thenReturn(storage);
            when(inspector.inspect(csv)).thenReturn(new FileContentInspector.Inspection(
                    size, "c".repeat(64), "d".repeat(32), "text/csv"));
            FileMetadata staleExisting = metadata(102L, "export/1/EXP-RECOVER.csv", "a".repeat(64), size - 1,
                    "CLEAN", 4L, Instant.now().plusSeconds(600));
            when(repository.findByObjectKey(1L, "export/1/EXP-RECOVER.csv"))
                    .thenReturn(Optional.of(staleExisting));
            AtomicReference<FileMetadata> inserted = new AtomicReference<>();
            doAnswer(invocation -> {
                FileMetadata input = invocation.getArgument(0);
                inserted.set(new FileMetadata(202L, input.tenantId(), input.objectKey(), input.originalName(),
                        input.mimeType(), input.sizeBytes(), input.sha256(), input.classification(),
                        input.institutionId(), input.scanStatus(), input.uploadedBy(), input.etag(),
                        input.lastError(), input.expiresAt(), input.createdAt(), input.updatedAt(), 0L));
                return null;
            }).when(repository).insert(any(FileMetadata.class));
            when(repository.findByObjectKey(eq(1L), argThat(key -> key.startsWith("export/1/EXP-RECOVER-"))))
                    .thenAnswer(invocation -> Optional.ofNullable(inserted.get()));
            when(storage.stat(argThat(key -> key.startsWith("export/1/EXP-RECOVER-"))))
                    .thenReturn(new PrivateObjectStorage.ObjectStat("d".repeat(32), size, "text/csv"));
            when(scanner.scan(csv)).thenReturn(ClamAvScanner.ScanResult.CLEAN);

            FileMetadata recovered = service.registerCleanFile(job("EXP-RECOVER"), csv, Instant.now().plusSeconds(600));

            assertThat(recovered.objectKey()).startsWith("export/1/EXP-RECOVER-").endsWith(".csv");
            assertThat(recovered.objectKey()).isNotEqualTo("export/1/EXP-RECOVER.csv");
            verify(storage).putObject(eq(recovered.objectKey()), any(InputStream.class), eq(size),
                    eq("text/csv;charset=UTF-8"));
            verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
            verify(storage, never()).deleteObject("export/1/EXP-RECOVER.csv");
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    @Test
    void leaseRecoveryWithExpiredExistingAllocatesNewUniqueObjectKey() throws Exception {
        Path csv = Files.createTempFile("export-expired-test-", ".csv");
        try {
            Files.writeString(csv, "客户编号,备注\r\nC-3,expired\r\n");
            long size = Files.size(csv);
            ExportFileService service = exportFileService();
            when(storageProvider.getIfAvailable()).thenReturn(storage);
            when(inspector.inspect(csv)).thenReturn(new FileContentInspector.Inspection(
                    size, "e".repeat(64), "f".repeat(32), "text/csv"));
            FileMetadata expiredExisting = metadata(103L, "export/1/EXP-EXPIRED.csv", "e".repeat(64), size,
                    "CLEAN", 4L, Instant.now().minusSeconds(60));
            when(repository.findByObjectKey(1L, "export/1/EXP-EXPIRED.csv"))
                    .thenReturn(Optional.of(expiredExisting));
            AtomicReference<FileMetadata> inserted = new AtomicReference<>();
            doAnswer(invocation -> {
                FileMetadata input = invocation.getArgument(0);
                inserted.set(new FileMetadata(203L, input.tenantId(), input.objectKey(), input.originalName(),
                        input.mimeType(), input.sizeBytes(), input.sha256(), input.classification(),
                        input.institutionId(), input.scanStatus(), input.uploadedBy(), input.etag(),
                        input.lastError(), input.expiresAt(), input.createdAt(), input.updatedAt(), 0L));
                return null;
            }).when(repository).insert(any(FileMetadata.class));
            when(repository.findByObjectKey(eq(1L), argThat(key -> key.startsWith("export/1/EXP-EXPIRED-"))))
                    .thenAnswer(invocation -> Optional.ofNullable(inserted.get()));
            when(storage.stat(argThat(key -> key.startsWith("export/1/EXP-EXPIRED-"))))
                    .thenReturn(new PrivateObjectStorage.ObjectStat("f".repeat(32), size, "text/csv"));
            when(scanner.scan(csv)).thenReturn(ClamAvScanner.ScanResult.CLEAN);

            FileMetadata recovered = service.registerCleanFile(job("EXP-EXPIRED"), csv, Instant.now().plusSeconds(600));

            assertThat(recovered.objectKey()).startsWith("export/1/EXP-EXPIRED-").endsWith(".csv");
            verify(storage).putObject(eq(recovered.objectKey()), any(InputStream.class), eq(size),
                    eq("text/csv;charset=UTF-8"));
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    @Test
    void leaseRecoveryRejectsOwnershipOrClassificationMismatch() throws Exception {
        Path csv = Files.createTempFile("export-owner-test-", ".csv");
        try {
            Files.writeString(csv, "客户编号,备注\r\nC-4,owner\r\n");
            long size = Files.size(csv);
            ExportFileService service = exportFileService();
            when(storageProvider.getIfAvailable()).thenReturn(storage);
            when(inspector.inspect(csv)).thenReturn(new FileContentInspector.Inspection(
                    size, "a".repeat(64), "b".repeat(32), "text/csv"));
            FileMetadata wrongOwner = metadata(104L, "export/1/EXP-OWNER.csv", "a".repeat(64), size,
                    "CLEAN", 99L, Instant.now().plusSeconds(600));
            when(repository.findByObjectKey(1L, "export/1/EXP-OWNER.csv"))
                    .thenReturn(Optional.of(wrongOwner));

            assertThatThrownBy(() -> service.registerCleanFile(job("EXP-OWNER"), csv, Instant.now().plusSeconds(600)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.CONFLICT);

            verify(storage, never()).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
            verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
            verify(repository, never()).insert(any(FileMetadata.class));
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    private ExportFileService exportFileService() {
        FileProperties fileProperties = new FileProperties();
        fileProperties.setEnabled(true);
        return new ExportFileService(repository, storageProvider, inspector, scanner,
                fileProperties, new ExportProperties());
    }

    private ExportJob job(String jobNo) {
        ExportJob job = new ExportJob();
        job.setTenantId(1L);
        job.setJobNo(jobNo);
        job.setRequestedBy(4L);
        return job;
    }

    private FileMetadata metadata(long id, String objectKey, String sha256, long sizeBytes,
                                  String scanStatus, Long uploadedBy, Instant expiresAt) {
        Instant now = Instant.now();
        return new FileMetadata(id, 1L, objectKey, "job.csv", "text/csv", sizeBytes, sha256,
                "EXPORT", null, scanStatus, uploadedBy, "b".repeat(32), null, expiresAt, now, now, 0L);
    }
}
