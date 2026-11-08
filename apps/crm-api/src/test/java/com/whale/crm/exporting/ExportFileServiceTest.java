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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
            FileProperties fileProperties = new FileProperties();
            fileProperties.setEnabled(true);
            ExportFileService service = new ExportFileService(repository, storageProvider, inspector, scanner,
                    fileProperties, new ExportProperties());
            when(storageProvider.getIfAvailable()).thenReturn(storage);
            when(inspector.inspect(csv)).thenReturn(new FileContentInspector.Inspection(
                    Files.size(csv), "a".repeat(64), "b".repeat(32), "text/csv"));
            when(repository.findByObjectKey(anyLong(), anyString())).thenReturn(Optional.empty());
            when(storage.stat(anyString())).thenReturn(new PrivateObjectStorage.ObjectStat(
                    "b".repeat(32), Files.size(csv), "text/csv"));
            when(scanner.scan(csv)).thenReturn(ClamAvScanner.ScanResult.FOUND);
            ExportJob job = new ExportJob();
            job.setTenantId(1L);
            job.setJobNo("EXP-SCAN");
            job.setRequestedBy(4L);

            assertThatThrownBy(() -> service.registerCleanFile(job, csv, Instant.now().plusSeconds(60)))
                    .isInstanceOf(BusinessException.class);

            verify(storage).deleteObject("export/1/EXP-SCAN.csv");
            verify(repository, never()).insert(any(FileMetadata.class));
        } finally {
            Files.deleteIfExists(csv);
        }
    }
}
