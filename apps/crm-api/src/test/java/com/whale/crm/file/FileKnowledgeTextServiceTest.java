package com.whale.crm.file;

import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.application.FileContentInspector;
import com.whale.crm.file.application.FileScopeService;
import com.whale.crm.file.application.KnowledgeTextContent;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.file.domain.FileRepository;
import com.whale.crm.file.infrastructure.FileProperties;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileKnowledgeTextServiceTest {
    private FileRepository repository;
    private PrivateObjectStorage storage;
    private FileApplicationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(FileRepository.class);
        storage = mock(PrivateObjectStorage.class);
        ObjectProvider<PrivateObjectStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storage);
        FileProperties properties = new FileProperties();
        properties.setEnabled(true);
        service = new FileApplicationService(repository, provider, mock(FileContentInspector.class),
                mock(ClamAvScanner.class), mock(FileScopeService.class), properties,
                mock(AuditService.class), mock(PlatformTransactionManager.class));
    }

    @Test
    void readsApprovedKnowledgeTextAsStrictUtf8() {
        FileMetadata metadata = metadata("text/markdown", "CLEAN", 100L);
        when(repository.findById(1L, 99L)).thenReturn(Optional.of(metadata));
        when(storage.getObject(metadata.objectKey())).thenReturn(new ByteArrayInputStream(
                "# 合成指南\n仅用于测试".getBytes(StandardCharsets.UTF_8)));

        KnowledgeTextContent result = service.readKnowledgeText(1L, 99L, 1L, 1_048_576L);

        assertThat(result.text()).contains("合成指南");
        assertThat(result.metadata().sha256()).isEqualTo("a".repeat(64));
    }

    @Test
    void rejectsNonTextNonCleanAndWrongInstitution() {
        FileMetadata pdf = metadata("application/pdf", "CLEAN", 100L);
        when(repository.findById(1L, 99L)).thenReturn(Optional.of(pdf));
        assertThatThrownBy(() -> service.readKnowledgeText(1L, 99L, 1L, 1_048_576L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("TXT/Markdown");

        FileMetadata pending = metadata("text/plain", "PENDING", 100L);
        when(repository.findById(1L, 99L)).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> service.readKnowledgeText(1L, 99L, 1L, 1_048_576L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("安全扫描");

        FileMetadata clean = metadata("text/plain", "CLEAN", 100L);
        when(repository.findById(1L, 99L)).thenReturn(Optional.of(clean));
        assertThatThrownBy(() -> service.readKnowledgeText(1L, 99L, 2L, 1_048_576L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("机构");
    }

    @Test
    void rejectsOversizedAndMalformedUtf8Content() {
        FileMetadata metadata = metadata("text/plain", "CLEAN", 100L);
        when(repository.findById(1L, 99L)).thenReturn(Optional.of(metadata));
        when(storage.getObject(metadata.objectKey())).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertThatThrownBy(() -> service.readKnowledgeText(1L, 99L, 1L, 2L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("1 MiB");

        when(storage.getObject(metadata.objectKey())).thenReturn(new ByteArrayInputStream(new byte[]{(byte) 0xC3, 0x28}));
        assertThatThrownBy(() -> service.readKnowledgeText(1L, 99L, 1L, 1_048_576L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("UTF-8");
    }

    private FileMetadata metadata(String mimeType, String scanStatus, long size) {
        return new FileMetadata(99L, 1L, "sealed/1/guide", "guide.md", mimeType, size,
                "a".repeat(64), "MATERIAL", 1L, scanStatus, 7L, "etag", null, null,
                Instant.now(), Instant.now(), 2L);
    }
}
