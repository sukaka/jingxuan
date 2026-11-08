package com.whale.crm.exporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.MobileSupport;
import com.whale.crm.exporting.application.CsvEncoder;
import com.whale.crm.exporting.application.ExportColumnPolicy;
import com.whale.crm.exporting.application.ExportExecutionService;
import com.whale.crm.exporting.application.ExportScopeService;
import com.whale.crm.exporting.domain.ExportAccessLog;
import com.whale.crm.exporting.domain.ExportDataRepository;
import com.whale.crm.exporting.domain.ExportJob;
import com.whale.crm.exporting.domain.ExportJobRepository;
import com.whale.crm.exporting.domain.ExportRestriction;
import com.whale.crm.exporting.domain.ExportScope;
import com.whale.crm.exporting.domain.ExportStatus;
import com.whale.crm.exporting.domain.ExportType;
import com.whale.crm.exporting.domain.StoredExportScope;
import com.whale.crm.exporting.infrastructure.ExportFileService;
import com.whale.crm.exporting.infrastructure.ExportProperties;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportExecutionServiceTest {
    @Mock ExportJobRepository repository;
    @Mock ExportDataRepository dataRepository;
    @Mock ExportScopeService scopeService;
    @Mock ExportFileService fileService;
    @Mock CurrentUserService currentUserService;
    @Mock MobileSupport mobileSupport;
    @Mock PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ExportScope all = new ExportScope(ExportScope.Kind.ALL, Set.of(), 4L, Set.of());
    private ExportExecutionService service;
    private ExportJob job;

    @BeforeEach
    void setUp() throws Exception {
        ExportProperties properties = new ExportProperties();
        properties.setMaxRows(10);
        properties.setPageSize(2);
        service = new ExportExecutionService(repository, dataRepository, scopeService, new ExportColumnPolicy(),
                new CsvEncoder(), fileService, currentUserService, mobileSupport, objectMapper, properties,
                transactionManager, 60);
        job = new ExportJob();
        job.setId(7L);
        job.setTenantId(1L);
        job.setJobNo("EXP-TEST");
        job.setRequestedBy(4L);
        job.setExportType(ExportType.CUSTOMER);
        job.setStatus(ExportStatus.RUNNING);
        job.setLeaseOwner("worker-a");
        job.setVersion(3L);
        job.setReason("核心CSV测试");
        job.setScopeJson(objectMapper.writeValueAsString(new StoredExportScope(all, null, ExportRestriction.none())));
        job.setFiltersJson(objectMapper.writeValueAsString(com.whale.crm.exporting.domain.ExportFilter.empty()));
        job.setColumnsJson("[\"customerNo\",\"mobile\",\"remark\"]");
        lenient().when(repository.findById(1L, 7L)).thenReturn(Optional.of(job));
        CurrentUser user = new CurrentUser(4L, 1L, "lead-test", "测试主管", "EMPLOYEE", 1L,
                Set.of("MANAGER_LEAD"), Set.of("export:create", "export:read", "customer:read"), Set.of());
        lenient().when(currentUserService.loadById(4L)).thenReturn(Optional.of(user));
        lenient().when(scopeService.executionScope(any(), any(), any())).thenReturn(all);
        lenient().when(repository.renewLease(anyLong(), anyLong(), anyString(), any())).thenReturn(1);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void generatedCsvContainsMaskedMobileAndFormulaSafeValues() throws Exception {
        when(dataRepository.count(any())).thenReturn(1L);
        when(dataRepository.page(any())).thenReturn(List.of(Map.of(
                "rowId", 1L, "customerNo", "C-X02", "mobileCipher", "synthetic-cipher", "remark", "=1+1")));
        when(mobileSupport.maskFromCipher("synthetic-cipher")).thenReturn("139****2222");
        AtomicReference<String> csv = new AtomicReference<>();
        when(fileService.registerCleanFile(any(), any(), any())).thenAnswer(invocation -> {
            csv.set(Files.readString(invocation.getArgument(1), StandardCharsets.UTF_8));
            Instant expiresAt = invocation.getArgument(2);
            return new FileMetadata(55L, 1L, "export/1/EXP-TEST.csv", "EXP-TEST.csv", "text/csv",
                    (long) csv.get().getBytes(StandardCharsets.UTF_8).length, "a".repeat(64), "EXPORT", null,
                    "CLEAN", 4L, null, null, expiresAt, Instant.now(), Instant.now(), 0L);
        });
        when(repository.markSucceeded(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), anyLong(),
                any(), anyString(), any())).thenReturn(1);

        service.execute(1L, 7L, "worker-a");

        assertThat(csv.get()).contains("C-X02,139****2222,'=1+1");
        assertThat(csv.get()).doesNotContain("synthetic-cipher").doesNotContain("mobileHash");
        verify(currentUserService).loadById(4L);
        verify(repository).markSucceeded(eq(1L), eq(7L), eq(3L), eq("worker-a"), eq(55L), eq(1L),
                any(), anyString(), any());
    }

    @Test
    void rowLimitFailsBeforeFileCreationAndAppendsDenial() throws Exception {
        when(dataRepository.count(any())).thenReturn(11L);
        when(repository.markFailed(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);

        service.execute(1L, 7L, "worker-a");

        verify(fileService, never()).registerCleanFile(any(), any(), any());
        ArgumentCaptor<ExportAccessLog> audit = ArgumentCaptor.forClass(ExportAccessLog.class);
        verify(repository).insertAccessLog(audit.capture());
        assertThat(audit.getValue().getDenialCode()).isEqualTo("ROW_LIMIT_EXCEEDED");
        assertThat(audit.getValue().getReason()).isEqualTo("核心CSV测试");
    }

    @Test
    void temporaryCsvIsDeletedWhenFileRegistrationFails() throws Exception {
        when(dataRepository.count(any())).thenReturn(0L);
        AtomicReference<Path> temporary = new AtomicReference<>();
        when(fileService.registerCleanFile(any(), any(), any())).thenAnswer(invocation -> {
            temporary.set(invocation.getArgument(1));
            assertThat(temporary.get()).exists();
            throw new BusinessException(ErrorCode.CONFLICT, "扫描失败");
        });
        when(repository.markFailed(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(1);

        service.execute(1L, 7L, "worker-a");

        assertThat(temporary.get()).doesNotExist();
    }
}
