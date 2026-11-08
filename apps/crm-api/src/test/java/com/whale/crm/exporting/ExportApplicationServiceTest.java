package com.whale.crm.exporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.exporting.api.dto.CreateExportRequest;
import com.whale.crm.exporting.application.ExportApplicationService;
import com.whale.crm.exporting.application.ExportColumnPolicy;
import com.whale.crm.exporting.application.ExportScopeService;
import com.whale.crm.exporting.domain.ExportAccessLog;
import com.whale.crm.exporting.domain.ExportJob;
import com.whale.crm.exporting.domain.ExportJobRepository;
import com.whale.crm.exporting.domain.ExportRestriction;
import com.whale.crm.exporting.domain.ExportScope;
import com.whale.crm.exporting.domain.ExportStatus;
import com.whale.crm.exporting.domain.ExportType;
import com.whale.crm.exporting.domain.StoredExportScope;
import com.whale.crm.exporting.infrastructure.ExportFileService;
import com.whale.crm.platform.security.CurrentUser;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportApplicationServiceTest {
    @Mock ExportJobRepository repository;
    @Mock ExportScopeService scopeService;
    @Mock ExportFileService fileService;
    @Mock PlatformTransactionManager transactionManager;

    private final AtomicReference<ExportJob> stored = new AtomicReference<>();
    private ExportApplicationService service;
    private CurrentUser lead;

    @BeforeEach
    void setUp() {
        service = new ExportApplicationService(repository, new ExportColumnPolicy(), scopeService, fileService,
                new ObjectMapper().findAndRegisterModules(), transactionManager);
        lead = new CurrentUser(4L, 1L, "lead-test", "测试主管", "EMPLOYEE", 1L,
                Set.of("MANAGER_LEAD"), Set.of("export:create", "export:read", "customer:read"), Set.of());
        ExportScope team = new ExportScope(ExportScope.Kind.TEAM, Set.of(1L), 4L, Set.of());
        lenient().when(scopeService.requestScope(any(), any(), any()))
                .thenReturn(new StoredExportScope(team, null, ExportRestriction.none()));
        lenient().when(repository.findByIdempotency(any(Long.class), any(Long.class), any(String.class)))
                .thenAnswer(invocation -> java.util.Optional.ofNullable(stored.get()));
        lenient().doAnswer(invocation -> {
            ExportJob job = invocation.getArgument(0);
            job.setId(91L);
            stored.set(job);
            return null;
        }).when(repository).insert(any());
        transactionManagerStub();
    }

    @Test
    void missingCreatePermissionIsDeniedWithReasonAudit() {
        CurrentUser manager = new CurrentUser(2L, 1L, "manager-test", "测试管家", "EMPLOYEE", 1L,
                Set.of("MANAGER"), Set.of("customer:read"), Set.of());

        assertThatThrownBy(() -> service.create(manager, request("X01 批量导出客户"), "x01-key"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        ArgumentCaptor<ExportAccessLog> captor = ArgumentCaptor.forClass(ExportAccessLog.class);
        verify(repository).insertAccessLog(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("REQUEST");
        assertThat(captor.getValue().getResult()).isEqualTo("DENIED");
        assertThat(captor.getValue().getReason()).isEqualTo("X01 批量导出客户");
        assertThat(captor.getValue().getDenialCode()).isEqualTo("EXPORT_CREATE_FORBIDDEN");
        verify(repository, never()).insert(any());
    }

    @Test
    void idempotentCreateReturnsOneJobAndDifferentPayloadConflicts() {
        ExportJob first = service.create(lead, request("X02 团队复核"), "x02-key");
        ExportJob repeated = service.create(lead, request("X02 团队复核"), "x02-key");

        assertThat(first.getId()).isEqualTo(91L);
        assertThat(repeated.getId()).isEqualTo(91L);
        assertThat(first.getStatus()).isEqualTo(ExportStatus.PENDING);
        verify(repository, org.mockito.Mockito.times(1)).insert(any());

        assertThatThrownBy(() -> service.create(lead, request("不同理由"), "x02-key"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void cipherAndBlindIndexColumnsAreRejected() {
        CreateExportRequest request = new CreateExportRequest(ExportType.CUSTOMER,
                List.of("customerNo", "mobileCipher"), "字段白名单测试", null, null);

        assertThatThrownBy(() -> service.create(lead, request, "column-key"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repository, never()).insert(any());
    }

    private CreateExportRequest request(String reason) {
        return new CreateExportRequest(ExportType.CUSTOMER,
                List.of("customerNo", "displayName", "mobile"), reason, null, null);
    }

    private void transactionManagerStub() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
    }
}
