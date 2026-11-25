package com.whale.crm.customer.application;

import com.whale.crm.customer.api.dto.MergeReversalActionRequest;
import com.whale.crm.customer.api.dto.MergeReversalResponse;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerIdentityRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.customer.domain.FollowupRepository;
import com.whale.crm.customer.domain.MergeCase;
import com.whale.crm.customer.domain.MergeCaseRepository;
import com.whale.crm.customer.domain.MergeResourceSnapshot;
import com.whale.crm.customer.domain.MergeResourceSnapshotRepository;
import com.whale.crm.customer.domain.MergeReversal;
import com.whale.crm.customer.domain.MergeReversalRepository;
import com.whale.crm.customer.domain.PointsLedgerRepository;
import com.whale.crm.customer.domain.TaskRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MergeReversalApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T08:00:00Z");
    private static final CurrentUser ADMIN = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("customer:merge_reverse"), Set.of());
    private static final CurrentUser LEAD_OUT_OF_SCOPE = new CurrentUser(99L, 1L, "lead99", "异组主管", "EMPLOYEE", 9L,
            Set.of("MANAGER_LEAD"), Set.of("customer:merge_reverse"), Set.of());

    private MergeCaseRepository mergeCaseRepository;
    private MergeReversalRepository reversalRepository;
    private MergeResourceSnapshotRepository snapshotRepository;
    private CustomerRepository customerRepository;
    private PointsLedgerRepository pointsLedgerRepository;
    private CustomerOrderOperations customerOrderOperations;
    private DataScopeService dataScopeService;
    private MergeReferenceGuard referenceGuard;
    private MergeReversalApplicationService service;

    @BeforeEach
    void setUp() {
        mergeCaseRepository = mock(MergeCaseRepository.class);
        reversalRepository = mock(MergeReversalRepository.class);
        snapshotRepository = mock(MergeResourceSnapshotRepository.class);
        customerRepository = mock(CustomerRepository.class);
        pointsLedgerRepository = mock(PointsLedgerRepository.class);
        customerOrderOperations = mock(CustomerOrderOperations.class);
        dataScopeService = mock(DataScopeService.class);
        referenceGuard = mock(MergeReferenceGuard.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.lookup(anyLong(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        CustomerScopeSupport scopeSupport = new CustomerScopeSupport(dataScopeService, customerRepository);
        service = new MergeReversalApplicationService(
                mergeCaseRepository,
                reversalRepository,
                snapshotRepository,
                customerRepository,
                mock(CustomerIdentityRepository.class),
                mock(CustomerTagRepository.class),
                mock(FollowupRepository.class),
                mock(TaskRepository.class),
                pointsLedgerRepository,
                customerOrderOperations,
                scopeSupport,
                idempotencyService,
                mock(AuditService.class),
                txManager,
                referenceGuard);
        when(dataScopeService.scopeFor(ADMIN, DataScopeService.RESOURCE_CUSTOMER)).thenReturn(DataScope.all());
        when(dataScopeService.scopeFor(LEAD_OUT_OF_SCOPE, DataScopeService.RESOURCE_CUSTOMER))
                .thenReturn(DataScope.team(Set.of(9L)));
    }

    @Test
    void approveAcquiresMergeCaseForUpdateBeforeCustomerForUpdateAndChecksScopeAfterLocks() {
        MergeReversal requested = reversal(10L, 20L, "REQUESTED", 3L, 4L, 0L);
        MergeReversal executed = reversal(10L, 20L, "EXECUTED", 3L, 4L, 1L);
        MergeCase mergeCase = mergeCase(20L, 100L, 200L, MergeCase.STATUS_CONFIRMED, 2L);
        Customer primary = customer(100L, 2L, 1L, 3L, false);
        Customer secondary = customer(200L, 2L, 1L, 4L, true);

        when(reversalRepository.findForUpdate(1L, 10L)).thenReturn(Optional.of(requested));
        when(mergeCaseRepository.findByIdForUpdate(1L, 20L)).thenReturn(Optional.of(mergeCase));
        when(customerRepository.findByIdForUpdate(1L, 100L)).thenReturn(Optional.of(primary));
        when(customerRepository.findByIdForUpdate(1L, 200L)).thenReturn(Optional.of(secondary));
        when(customerRepository.restore(1L, 200L, 4L)).thenReturn(1);
        when(snapshotRepository.findByMergeCaseId(1L, 20L))
                .thenReturn(List.of(new MergeResourceSnapshot(1L, 20L, "ORDER", "300")));
        when(pointsLedgerRepository.sumDelta(1L, 100L)).thenReturn(0L);
        when(pointsLedgerRepository.sumDelta(1L, 200L)).thenReturn(0L);
        when(mergeCaseRepository.updateStatus(eq(1L), eq(20L), eq(2L), eq(MergeCase.STATUS_REVERSED), eq(1L), any()))
                .thenReturn(1);
        when(reversalRepository.updateStatus(eq(1L), eq(10L), eq(0L), eq("EXECUTED"), eq("核实恢复"), eq(1L),
                any(), any(), anyString(), isNull())).thenReturn(1);
        when(reversalRepository.findById(1L, 10L)).thenReturn(Optional.of(executed));

        MergeReversalResponse response = service.approve(
                ADMIN, 10L, new MergeReversalActionRequest("核实恢复", 0L), "k-approve-1");

        assertThat(response.status()).isEqualTo("EXECUTED");
        InOrder order = inOrder(reversalRepository, mergeCaseRepository, customerRepository, dataScopeService,
                referenceGuard);
        order.verify(reversalRepository).findForUpdate(1L, 10L);
        order.verify(mergeCaseRepository).findByIdForUpdate(1L, 20L);
        order.verify(customerRepository).findByIdForUpdate(1L, 100L);
        order.verify(customerRepository).findByIdForUpdate(1L, 200L);
        order.verify(dataScopeService).scopeFor(ADMIN, DataScopeService.RESOURCE_CUSTOMER);
        order.verify(referenceGuard).requireUnreferenced(1L, List.of(300L), List.of());
        order.verify(mergeCaseRepository).updateStatus(eq(1L), eq(20L), eq(2L),
                eq(MergeCase.STATUS_REVERSED), eq(1L), any());

        verify(mergeCaseRepository, never()).findById(anyLong(), anyLong());
        verify(customerRepository, never()).findById(anyLong(), anyLong());
        verify(customerOrderOperations).reassignSpecific(1L, List.of(300L), List.of(), 100L, 200L);
        verify(customerRepository).refreshNextFollowupAt(1L, 100L);
        verify(customerRepository).refreshNextFollowupAt(1L, 200L);
    }

    @Test
    void approveWithEmptySnapshotsTransitionsToManualReviewWithoutRefreshingNextFollowupAt() {
        MergeReversal requested = reversal(10L, 20L, "REQUESTED", 3L, 4L, 0L);
        MergeReversal manualReview = reversal(10L, 20L, "MANUAL_REVIEW", 3L, 4L, 1L);
        MergeCase mergeCase = mergeCase(20L, 100L, 200L, MergeCase.STATUS_CONFIRMED, 2L);
        Customer primary = customer(100L, 2L, 1L, 3L, false);
        Customer secondary = customer(200L, 2L, 1L, 4L, true);

        when(reversalRepository.findForUpdate(1L, 10L)).thenReturn(Optional.of(requested));
        when(mergeCaseRepository.findByIdForUpdate(1L, 20L)).thenReturn(Optional.of(mergeCase));
        when(customerRepository.findByIdForUpdate(1L, 100L)).thenReturn(Optional.of(primary));
        when(customerRepository.findByIdForUpdate(1L, 200L)).thenReturn(Optional.of(secondary));
        when(customerRepository.restore(1L, 200L, 4L)).thenReturn(1);
        when(snapshotRepository.findByMergeCaseId(1L, 20L)).thenReturn(List.of());
        when(reversalRepository.updateStatus(eq(1L), eq(10L), eq(0L), eq("MANUAL_REVIEW"), eq("旧合并无快照"),
                eq(1L), any(), isNull(), anyString(), isNull())).thenReturn(1);
        when(reversalRepository.findById(1L, 10L)).thenReturn(Optional.of(manualReview));

        MergeReversalResponse response = service.approve(
                ADMIN, 10L, new MergeReversalActionRequest("旧合并无快照", 0L), "k-manual-review");

        assertThat(response.status()).isEqualTo("MANUAL_REVIEW");
        verify(customerRepository, never()).refreshNextFollowupAt(anyLong(), anyLong());
        verify(customerRepository, never()).refreshPointsCached(anyLong(), anyLong(), anyInt());
    }

    @Test
    void approveAcquiresCustomerLocksInAscendingOrderAfterMergeCaseForUpdate() {
        MergeReversal requested = reversal(10L, 20L, "REQUESTED", 3L, 4L, 0L);
        MergeReversal executed = reversal(10L, 20L, "EXECUTED", 3L, 4L, 1L);
        MergeCase mergeCase = mergeCase(20L, 200L, 100L, MergeCase.STATUS_CONFIRMED, 2L);
        Customer primary = customer(200L, 2L, 1L, 3L, false);
        Customer secondary = customer(100L, 2L, 1L, 4L, true);

        when(reversalRepository.findForUpdate(1L, 10L)).thenReturn(Optional.of(requested));
        when(mergeCaseRepository.findByIdForUpdate(1L, 20L)).thenReturn(Optional.of(mergeCase));
        when(customerRepository.findByIdForUpdate(1L, 100L)).thenReturn(Optional.of(secondary));
        when(customerRepository.findByIdForUpdate(1L, 200L)).thenReturn(Optional.of(primary));
        when(customerRepository.restore(1L, 100L, 4L)).thenReturn(1);
        when(snapshotRepository.findByMergeCaseId(1L, 20L))
                .thenReturn(List.of(new MergeResourceSnapshot(1L, 20L, "ORDER", "300")));
        when(mergeCaseRepository.updateStatus(eq(1L), eq(20L), eq(2L), eq(MergeCase.STATUS_REVERSED), eq(1L), any()))
                .thenReturn(1);
        when(reversalRepository.updateStatus(eq(1L), eq(10L), eq(0L), eq("EXECUTED"), eq("逆序客户恢复"), eq(1L),
                any(), any(), anyString(), isNull())).thenReturn(1);
        when(reversalRepository.findById(1L, 10L)).thenReturn(Optional.of(executed));

        service.approve(ADMIN, 10L, new MergeReversalActionRequest("逆序客户恢复", 0L), "k-approve-asc");

        InOrder order = inOrder(reversalRepository, mergeCaseRepository, customerRepository);
        order.verify(reversalRepository).findForUpdate(1L, 10L);
        order.verify(mergeCaseRepository).findByIdForUpdate(1L, 20L);
        order.verify(customerRepository).findByIdForUpdate(1L, 100L);
        order.verify(customerRepository).findByIdForUpdate(1L, 200L);
        verify(mergeCaseRepository, never()).findById(anyLong(), anyLong());
    }

    @Test
    void approveReturnsNotFoundBeforeConflictForOutOfScopeCallerWithWrongVersionAndTerminalStatus() {
        MergeReversal terminalReversal = reversal(10L, 20L, "EXECUTED", 3L, 4L, 1L);
        MergeCase reversedCase = mergeCase(20L, 100L, 200L, MergeCase.STATUS_REVERSED, 3L);
        Customer primary = customer(100L, 2L, 1L, 3L, false);
        Customer secondary = customer(200L, 2L, 1L, 4L, false);

        when(reversalRepository.findForUpdate(1L, 10L)).thenReturn(Optional.of(terminalReversal));
        when(mergeCaseRepository.findByIdForUpdate(1L, 20L)).thenReturn(Optional.of(reversedCase));
        when(customerRepository.findByIdForUpdate(1L, 100L)).thenReturn(Optional.of(primary));
        when(customerRepository.findByIdForUpdate(1L, 200L)).thenReturn(Optional.of(secondary));

        assertThatThrownBy(() -> service.approve(
                LEAD_OUT_OF_SCOPE, 10L, new MergeReversalActionRequest("越权探测", 999L), "k-oos-404"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(customerRepository, never()).restore(anyLong(), anyLong(), anyLong());
        verify(customerRepository, never()).refreshNextFollowupAt(anyLong(), anyLong());
        verify(reversalRepository, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyString(),
                any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void approveRejectsNonConfirmedMergeCaseWithConflictWithoutMutatingOrCallingMarkError() {
        MergeReversal requested = reversal(10L, 20L, "REQUESTED", 3L, 4L, 0L);
        MergeCase reversedCase = mergeCase(20L, 100L, 200L, MergeCase.STATUS_REVERSED, 3L);
        Customer primary = customer(100L, 2L, 1L, 3L, false);
        Customer secondary = customer(200L, 2L, 1L, 4L, false);

        when(reversalRepository.findForUpdate(1L, 10L)).thenReturn(Optional.of(requested));
        when(mergeCaseRepository.findByIdForUpdate(1L, 20L)).thenReturn(Optional.of(reversedCase));
        when(customerRepository.findByIdForUpdate(1L, 100L)).thenReturn(Optional.of(primary));
        when(customerRepository.findByIdForUpdate(1L, 200L)).thenReturn(Optional.of(secondary));

        assertThatThrownBy(() -> service.approve(
                ADMIN, 10L, new MergeReversalActionRequest("重复更正", 0L), "k-conflict-reversed"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(customerRepository, never()).restore(anyLong(), anyLong(), anyLong());
        verify(mergeCaseRepository, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any());
        verify(reversalRepository, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyString(),
                any(), anyLong(), any(), any(), any(), any());
        verify(customerRepository, never()).refreshPointsCached(anyLong(), anyLong(), anyInt());
        verify(customerRepository, never()).refreshNextFollowupAt(anyLong(), anyLong());
    }

    private static MergeReversal reversal(long id, long caseId, String status, long primaryVersion,
                                          long secondaryVersion, long version) {
        return new MergeReversal(id, 1L, caseId, status, "误合并", null, primaryVersion, secondaryVersion,
                1L, NOW, null, null, null, null, null, version);
    }

    private static MergeCase mergeCase(long id, long primaryId, long secondaryId, String status, long version) {
        MergeCase mergeCase = new MergeCase();
        mergeCase.setId(id);
        mergeCase.setTenantId(1L);
        mergeCase.setPrimaryCustomerId(primaryId);
        mergeCase.setSecondaryCustomerId(secondaryId);
        mergeCase.setStatus(status);
        mergeCase.setVersion(version);
        return mergeCase;
    }

    private static Customer customer(long id, long ownerStaffId, long teamId, long version, boolean deleted) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setTenantId(1L);
        customer.setOwnerStaffId(ownerStaffId);
        customer.setTeamId(teamId);
        customer.setVersion(version);
        customer.setDeleted(deleted);
        return customer;
    }
}
