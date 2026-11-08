package com.whale.crm.file;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileExportRegistrationTest {

    @Test
    void existingExportServiceRegistersOnlyScannedCleanCsv() throws Exception {
        Path path = Files.createTempFile("export-registration-", ".csv");
        byte[] content = "code,note\r\nC-1,\"合成,内容\"\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(path, content);
        FileRepository repository = mock(FileRepository.class);
        PrivateObjectStorage storage = mock(PrivateObjectStorage.class);
        AtomicReference<FileMetadata> persisted = new AtomicReference<>();
        when(repository.findByObjectKey(1, "export/1/IT-EXP-CLEAN.csv")).thenAnswer(invocation ->
                Optional.ofNullable(persisted.get()));
        org.mockito.Mockito.doAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return null;
        }).when(repository).insert(any());
        when(storage.stat(anyString())).thenReturn(new PrivateObjectStorage.ObjectStat(
                HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content)), content.length, "text/csv"));

        try (ClamAvTestServer server = new ClamAvTestServer("stream: OK\0")) {
            ExportFileService service = service(repository, storage, server);
            Instant expiresAt = Instant.now().plusSeconds(300);

            FileMetadata metadata = service.registerCleanFile(job("IT-EXP-CLEAN"), path, expiresAt);

            assertThat(metadata.classification()).isEqualTo("EXPORT");
            assertThat(metadata.scanStatus()).isEqualTo("CLEAN");
            assertThat(metadata.mimeType()).isEqualTo("text/csv");
            assertThat(metadata.institutionId()).isNull();
            assertThat(metadata.expiresAt()).isEqualTo(expiresAt);
            assertThat(server.awaitReceipt().content()).isEqualTo(content);
            verify(storage).putObject("export/1/IT-EXP-CLEAN.csv", content, "text/csv;charset=UTF-8");
            verify(repository).insert(any());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void foundExportIsNotRegisteredAndUploadedObjectIsRemoved() throws Exception {
        Path path = Files.createTempFile("export-registration-found-", ".csv");
        Files.writeString(path, "code,note\r\nC-1,synthetic\r\n", java.nio.charset.StandardCharsets.UTF_8);
        FileRepository repository = mock(FileRepository.class);
        PrivateObjectStorage storage = mock(PrivateObjectStorage.class);
        when(repository.findByObjectKey(org.mockito.ArgumentMatchers.anyLong(), anyString())).thenReturn(Optional.empty());
        when(storage.stat(anyString())).thenAnswer(invocation -> {
            byte[] content = Files.readAllBytes(path);
            return new PrivateObjectStorage.ObjectStat(
                    HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content)), content.length, "text/csv");
        });

        try (ClamAvTestServer server = new ClamAvTestServer("stream: Eicar-Test-Signature FOUND\0")) {
            ExportFileService service = service(repository, storage, server);

            assertThatThrownBy(() -> service.registerCleanFile(job("IT-EXP-FOUND"), path, Instant.now().plusSeconds(300)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ClamAV");

            verify(repository, never()).insert(any());
            verify(storage).deleteObject("export/1/IT-EXP-FOUND.csv");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private ExportFileService service(FileRepository repository, PrivateObjectStorage storage, ClamAvTestServer server) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivateObjectStorage> storageProvider = mock(ObjectProvider.class);
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        FileProperties fileProperties = new FileProperties();
        fileProperties.setEnabled(true);
        fileProperties.setMaxFileSizeBytes(4096);
        return new ExportFileService(repository, storageProvider, new FileContentInspector(),
                new ClamAvScanner(server.host(), server.port(), 2000, 2000), fileProperties, new ExportProperties());
    }

    private ExportJob job(String jobNo) {
        ExportJob job = new ExportJob();
        job.setTenantId(1L);
        job.setJobNo(jobNo);
        job.setRequestedBy(4L);
        return job;
    }
}
