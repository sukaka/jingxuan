package com.whale.crm.customer.batchtag.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.batchtag.domain.BatchTagJob;
import com.whale.crm.customer.batchtag.domain.BatchTagJobAttempt;
import com.whale.crm.customer.batchtag.domain.BatchTagJobCounts;
import com.whale.crm.customer.batchtag.domain.BatchTagJobItem;
import com.whale.crm.customer.batchtag.domain.BatchTagJobRepository;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchTagJobExecutionServiceTest {
    private final BatchTagJobRepository repository = mock(BatchTagJobRepository.class);
    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final CustomerTagRepository tagRepository = mock(CustomerTagRepository.class);
    private final CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final DictionaryApplicationService dictionaryService = mock(DictionaryApplicationService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final NotificationRequestService notificationService = mock(NotificationRequestService.class);
    private final AtomicReference<String> leaseOwner = new AtomicReference<>();
    private final CurrentUser actor = new CurrentUser(2, 1, "manager", "合成管家", "EMPLOYEE", 1L,
            Set.of("MANAGER"), Set.of("customer:write", "customer:read"), Set.of());
    private BatchTagJobExecutionService service;

    @BeforeEach
    void setUp() {
        service = new BatchTagJobExecutionService(repository, customerRepository, tagRepository, scopeSupport,
                currentUserService, dictionaryService, auditService, idempotencyService, notificationService,
                new BatchTagJson(new ObjectMapper()), new TestTransactionManager(), 60000);
        when(repository.claimJob(eq(1L), eq(1L), anyLong(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    leaseOwner.set(invocation.getArgument(4));
                    return 1;
                });
        when(repository.findJobById(1L, 1L)).thenAnswer(invocation -> Optional.of(
                job(BatchTagJob.RUNNING, 6, leaseOwner.get())));
        when(repository.renewLease(eq(1L), eq(1L), anyLong(), anyString(), any())).thenReturn(1);
        when(repository.finishJob(eq(1L), eq(1L), anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        when(currentUserService.loadById(2)).thenReturn(Optional.of(actor));
        when(scopeSupport.scope(actor)).thenReturn(DataScope.all());
        when(scopeSupport.isVisible(any(), eq(actor), any())).thenReturn(true);
        when(repository.findConfirmedMergeTarget(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(repository.updateItemResult(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any(), any(),
                any(), any())).thenReturn(1);
        Customer customer = new Customer();
        customer.setId(11L);
        customer.setTenantId(1L);
        customer.setOwnerStaffId(2L);
        customer.setTeamId(1L);
        when(customerRepository.findByIdForUpdate(1L, 11)).thenReturn(Optional.of(customer));
    }

    @Test
    void dictionaryFailureCompletesFailedJobInsteadOfLeavingRunning() {
        BatchTagJobItem item = item(BatchTagJobItem.PENDING);
        when(repository.findWorkItems(1L, 1)).thenReturn(List.of(item), List.of());
        when(repository.findItemForUpdate(1L, 10)).thenReturn(Optional.of(item));
        when(repository.countItemResults(1L, 1)).thenReturn(new BatchTagJobCounts(0, 0, 0, 1));
        doThrow(new BusinessException(ErrorCode.CONFLICT, "inactive")).when(dictionaryService)
                .requireActiveOption(1L, "customerTags", "VIP");

        service.run(job(BatchTagJob.PENDING, 5, null), idempotency("EXECUTE"));

        verify(repository).updateItemResult(eq(1L), eq(10L), eq(7L), eq(BatchTagJobItem.FAILED),
                eq("TAG_DICTIONARY_INACTIVE"), anyString(), isNull(), isNull(), eq("[]"), any());
        verify(repository).finishJob(eq(1L), eq(1L), eq(6L), eq(leaseOwner.get()), eq(BatchTagJob.FAILED),
                eq(new BatchTagJobCounts(0, 0, 0, 1)), anyString(), any());
        verify(tagRepository, never()).insertIfAbsent(anyLong(), anyLong(), anyString(), anyLong());
        ArgumentCaptor<BatchTagJobAttempt> attempt = ArgumentCaptor.forClass(BatchTagJobAttempt.class);
        verify(repository).insertJobAttempt(attempt.capture());
        assertThat(attempt.getValue().status()).isEqualTo(BatchTagJob.FAILED);
        assertThat(attempt.getValue().triggerType()).isEqualTo("EXECUTE");
    }

    @Test
    void retryChangesOnlyFailedItemAndPreservesUnknownHistory() {
        BatchTagJobItem item = item(BatchTagJobItem.FAILED);
        when(repository.findWorkItems(1L, 1)).thenReturn(List.of(item), List.of());
        when(repository.findItemForUpdate(1L, 10)).thenReturn(Optional.of(item));
        when(repository.countItemResults(1L, 1)).thenReturn(new BatchTagJobCounts(1, 0, 0, 0));
        when(tagRepository.findTagCodes(1L, 11)).thenReturn(List.of("LEGACY_UNKNOWN"),
                List.of("LEGACY_UNKNOWN", "VIP"));
        when(tagRepository.insertIfAbsent(1L, 11, "VIP", 2L)).thenReturn(1);

        service.run(job(BatchTagJob.PARTIAL_FAILED, 5, null), idempotency("RETRY"));

        verify(tagRepository).insertIfAbsent(1L, 11, "VIP", 2L);
        verify(tagRepository, never()).delete(anyLong(), anyLong(), anyString());
        verify(repository).updateItemResult(eq(1L), eq(10L), eq(7L), eq(BatchTagJobItem.SUCCEEDED), isNull(),
                anyString(), eq("[\"LEGACY_UNKNOWN\"]"), eq("[\"LEGACY_UNKNOWN\",\"VIP\"]"), eq("[\"VIP\"]"), any());
        verify(repository).finishJob(eq(1L), eq(1L), eq(6L), eq(leaseOwner.get()), eq(BatchTagJob.SUCCEEDED),
                eq(new BatchTagJobCounts(1, 0, 0, 0)), isNull(), any());
        verify(idempotencyService).recordSuccess(eq(1L), eq("customer:batch_tag:retry"), eq("key"),
                eq("BATCH_TAG_JOB"), eq("1"), eq("payload"));
        ArgumentCaptor<BatchTagJobAttempt> attempt = ArgumentCaptor.forClass(BatchTagJobAttempt.class);
        verify(repository).insertJobAttempt(attempt.capture());
        assertThat(attempt.getValue().triggerType()).isEqualTo("RETRY");
    }

    @Test
    void revokedRequesterIsSkippedPerItemWithoutTouchingCustomerOrTags() {
        BatchTagJobItem item = item(BatchTagJobItem.PENDING);
        when(repository.findWorkItems(1L, 1)).thenReturn(List.of(item), List.of());
        when(repository.findItemForUpdate(1L, 10)).thenReturn(Optional.of(item));
        when(repository.countItemResults(1L, 1)).thenReturn(new BatchTagJobCounts(0, 0, 1, 0));
        when(currentUserService.loadById(2)).thenReturn(Optional.empty());

        service.run(job(BatchTagJob.PENDING, 5, null), idempotency("EXECUTE"));

        verify(repository).updateItemResult(eq(1L), eq(10L), eq(7L), eq(BatchTagJobItem.SKIPPED),
                eq("PERMISSION_REVOKED"), anyString(), isNull(), isNull(), eq("[]"), any());
        verify(customerRepository, never()).findByIdForUpdate(anyLong(), anyLong());
        verify(tagRepository, never()).insertIfAbsent(anyLong(), anyLong(), anyString(), anyLong());
        verify(repository).finishJob(eq(1L), eq(1L), eq(6L), eq(leaseOwner.get()),
                eq(BatchTagJob.PARTIAL_FAILED), eq(new BatchTagJobCounts(0, 0, 1, 0)), isNull(), any());
        verify(notificationService, never()).request(any());
    }

    private BatchTagJobExecutionService.ExecutionIdempotency idempotency(String trigger) {
        return new BatchTagJobExecutionService.ExecutionIdempotency(
                "customer:batch_tag:" + trigger.toLowerCase(), "key", "payload", trigger);
    }

    private BatchTagJob job(String status, long version, String owner) {
        return new BatchTagJob(1L, 1L, "BT-V34", "BP-V34", 2L, BatchTagJob.ADD, "[\"VIP\"]", "[]",
                "FILTER", null, "{}", "{}", "create-key", null, status, 1, 0, 0, 0, 0,
                0, owner, null, null, null, null, null, version);
    }

    private BatchTagJobItem item(String status) {
        return new BatchTagJobItem(10L, 1L, 1L, 11L, "C0000000011", "合成客户", 2L, 1L,
                "[\"VIP\"]", status, null, null, null, null, null, 1, null, 7L);
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
