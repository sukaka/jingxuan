package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.AssignCustomerOwnerRequest;
import com.whale.crm.customer.api.dto.BatchAssignCustomerOwnerRequest;
import com.whale.crm.customer.api.dto.BatchAssignCustomerOwnerResponse;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerOwnerHistory;
import com.whale.crm.customer.domain.CustomerOwnerHistoryRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTransferRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerAssignmentApplicationServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private CustomerTransferRepository transferRepository;
    @Mock
    private CustomerOwnerHistoryRepository ownerHistoryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CustomerScopeSupport scopeSupport;
    @Mock
    private StewardDirectory stewardDirectory;
    @Mock
    private AuditService auditService;
    @Mock
    private IdempotencyService idempotencyService;

    private CustomerAssignmentApplicationService service;

    @BeforeEach
    void setUp() {
        service = new CustomerAssignmentApplicationService(customerRepository, transferRepository,
                ownerHistoryRepository, userRepository, scopeSupport, stewardDirectory, auditService,
                idempotencyService, new ObjectMapper());
    }

    @Test
    void resolveChangeMode_classifiesDirectBatchAndTransfer() {
        assertThat(CustomerAssignmentApplicationService.resolveChangeMode(null))
                .isEqualTo(CustomerAssignmentApplicationService.MODE_DIRECT_ASSIGN);
        assertThat(CustomerAssignmentApplicationService.resolveChangeMode("DIRECT_ASSIGN"))
                .isEqualTo(CustomerAssignmentApplicationService.MODE_DIRECT_ASSIGN);
        assertThat(CustomerAssignmentApplicationService.resolveChangeMode("BATCH-ABC123"))
                .isEqualTo(CustomerAssignmentApplicationService.MODE_BATCH_ASSIGN);
        assertThat(CustomerAssignmentApplicationService.resolveChangeMode("TR-42"))
                .isEqualTo(CustomerAssignmentApplicationService.MODE_TRANSFER_APPROVAL);
    }

    @Test
    void assignOwner_rejectsNonSupervisorRoleEvenIfPermissionPresent() {
        CurrentUser customUser = currentUser(20L, 1L, Set.of("OPERATION"));
        SysUser target = activeSteward(3L, "新管家", 1L);
        when(idempotencyService.lookup(eq(1L), any(), eq(20L), any(), any())).thenReturn(Optional.empty());
        when(userRepository.findById(1L, 3L)).thenReturn(Optional.of(target));
        when(stewardDirectory.isEligibleSteward(1L, target)).thenReturn(true);

        assertThatThrownBy(() -> service.assignOwner(1L, customUser, 100L,
                new AssignCustomerOwnerRequest(3L, "分配", 0L), "k-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(customerRepository, never()).updateOwner(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void assignOwner_rejectsLeadCrossTeamTarget() {
        CurrentUser lead1 = currentUser(4L, 1L, Set.of("MANAGER_LEAD"));
        SysUser team2Steward = activeSteward(3L, "李强", 2L);
        when(idempotencyService.lookup(eq(1L), any(), eq(4L), any(), any())).thenReturn(Optional.empty());
        when(userRepository.findById(1L, 3L)).thenReturn(Optional.of(team2Steward));
        when(stewardDirectory.isEligibleSteward(1L, team2Steward)).thenReturn(true);

        assertThatThrownBy(() -> service.assignOwner(1L, lead1, 100L,
                new AssignCustomerOwnerRequest(3L, "跨组分配", 0L), "k-2"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void assignOwner_rejectsLeadWhenCustomerHasNullTeam() {
        CurrentUser lead1 = currentUser(4L, 1L, Set.of("MANAGER_LEAD"));
        SysUser team1Steward = activeSteward(2L, "王丽", 1L);
        Customer customer = customer(100L, 4L, null, 0L);
        when(idempotencyService.lookup(eq(1L), any(), eq(4L), any(), any())).thenReturn(Optional.empty());
        when(userRepository.findById(1L, 2L)).thenReturn(Optional.of(team1Steward));
        when(stewardDirectory.isEligibleSteward(1L, team1Steward)).thenReturn(true);
        when(scopeSupport.requireVisibleForUpdate(1L, 100L, lead1)).thenReturn(customer);

        assertThatThrownBy(() -> service.assignOwner(1L, lead1, 100L,
                new AssignCustomerOwnerRequest(2L, "无团队客户", 0L), "k-3"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void batchAssignOwners_rejectsDuplicateCustomerIds() {
        CurrentUser admin = currentUser(1L, null, Set.of("ADMIN"));
        BatchAssignCustomerOwnerRequest request = new BatchAssignCustomerOwnerRequest(
                List.of(new BatchAssignCustomerOwnerRequest.BatchAssignItem(10L, 0L),
                        new BatchAssignCustomerOwnerRequest.BatchAssignItem(10L, 0L)),
                3L, "重复项");

        assertThatThrownBy(() -> service.batchAssignOwners(1L, admin, request, "k-dup"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void batchAssignOwners_skipsSameOwnerAndAssignsOthers() {
        CurrentUser lead1 = currentUser(4L, 1L, Set.of("MANAGER_LEAD"));
        SysUser target = activeSteward(12L, "周管家", 1L);
        SysUser oldOwner = activeSteward(2L, "王丽", 1L);
        Customer c1 = customer(101L, 2L, 1L, 1L);
        Customer c2 = customer(102L, 12L, 1L, 2L);
        DataScope teamScope = DataScope.team(Set.of(1L));

        when(idempotencyService.lookup(eq(1L), any(), eq(4L), any(), any())).thenReturn(Optional.empty());
        when(userRepository.findById(1L, 12L)).thenReturn(Optional.of(target));
        when(userRepository.findById(1L, 2L)).thenReturn(Optional.of(oldOwner));
        when(stewardDirectory.isEligibleSteward(1L, target)).thenReturn(true);
        when(scopeSupport.scope(lead1)).thenReturn(teamScope);
        when(customerRepository.findByIdForUpdate(1L, 101L)).thenReturn(Optional.of(c1));
        when(customerRepository.findByIdForUpdate(1L, 102L)).thenReturn(Optional.of(c2));
        when(scopeSupport.isVisible(teamScope, lead1, c1)).thenReturn(true);
        when(scopeSupport.isVisible(teamScope, lead1, c2)).thenReturn(true);
        when(transferRepository.existsPendingForCustomer(1L, 101L)).thenReturn(false);
        when(customerRepository.updateOwner(1L, 101L, 1L, 12L, 1L)).thenReturn(1);
        doAnswer(invocation -> {
            CustomerOwnerHistory history = invocation.getArgument(0);
            history.setId(901L);
            return null;
        }).when(ownerHistoryRepository).insert(any(CustomerOwnerHistory.class));

        BatchAssignCustomerOwnerRequest request = new BatchAssignCustomerOwnerRequest(
                List.of(new BatchAssignCustomerOwnerRequest.BatchAssignItem(102L, 2L),
                        new BatchAssignCustomerOwnerRequest.BatchAssignItem(101L, 1L)),
                12L, "休假移交");

        BatchAssignCustomerOwnerResponse response = service.batchAssignOwners(1L, lead1, request, "k-batch");

        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.assignedCount()).isEqualTo(1);
        assertThat(response.skippedCount()).isEqualTo(1);
        assertThat(response.batchRef()).startsWith("BATCH-");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).customerId()).isEqualTo(102L);
        assertThat(response.items().get(0).status()).isEqualTo("SKIPPED");
        assertThat(response.items().get(1).customerId()).isEqualTo(101L);
        assertThat(response.items().get(1).status()).isEqualTo("ASSIGNED");
        assertThat(response.items().get(1).historyId()).isEqualTo(901L);
        verify(customerRepository, never()).updateOwner(eq(1L), eq(102L), anyLong(), anyLong(), any());
    }

    @Test
    void batchAssignOwners_allSameOwnerThrowsConflict() {
        CurrentUser lead1 = currentUser(4L, 1L, Set.of("MANAGER_LEAD"));
        SysUser target = activeSteward(12L, "周管家", 1L);
        Customer c2 = customer(102L, 12L, 1L, 2L);
        DataScope teamScope = DataScope.team(Set.of(1L));

        when(idempotencyService.lookup(eq(1L), any(), eq(4L), any(), any())).thenReturn(Optional.empty());
        when(userRepository.findById(1L, 12L)).thenReturn(Optional.of(target));
        when(stewardDirectory.isEligibleSteward(1L, target)).thenReturn(true);
        when(scopeSupport.scope(lead1)).thenReturn(teamScope);
        when(customerRepository.findByIdForUpdate(1L, 102L)).thenReturn(Optional.of(c2));
        when(scopeSupport.isVisible(teamScope, lead1, c2)).thenReturn(true);

        BatchAssignCustomerOwnerRequest request = new BatchAssignCustomerOwnerRequest(
                List.of(new BatchAssignCustomerOwnerRequest.BatchAssignItem(102L, 2L)), 12L, "重复分配");

        assertThatThrownBy(() -> service.batchAssignOwners(1L, lead1, request, "k-same"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void searchLogsAndTransfers_emptyScopeShortCircuits() {
        CurrentUser inst = new CurrentUser(8L, 1L, "inst1", "机构前台", "INSTITUTION", null,
                Set.of("INSTITUTION_STAFF"), Set.of(), Set.of(1L));
        DataScope instScope = DataScope.institution(Set.of(1L));
        when(scopeSupport.scope(inst)).thenReturn(instScope);
        when(scopeSupport.isEmptyScope(instScope)).thenReturn(true);

        assertThat(service.searchOwnershipLogs(1L, inst, null, null, null, 1, 20).items()).isEmpty();
        assertThat(service.searchScopedTransfers(1L, inst, null, null, null, 1, 20).items()).isEmpty();
        verify(ownerHistoryRepository, never()).searchLogs(anyLong(), any(), any(), anyLong(), any(), any(), any(),
                eq(0), eq(20));
        verify(transferRepository, never()).searchScopedRows(anyLong(), any(), any(), anyLong(), any(), any(), any(),
                eq(0), eq(20));
    }

    private static CurrentUser currentUser(long userId, Long teamId, Set<String> roles) {
        return new CurrentUser(userId, 1L, "u" + userId, "用户" + userId, "EMPLOYEE", teamId,
                roles, Set.of("customer:read", "customer:transfer"), Set.of());
    }

    private static SysUser activeSteward(long id, String name, Long teamId) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(1L);
        user.setDisplayName(name);
        user.setTeamId(teamId);
        user.setUserType("EMPLOYEE");
        user.setStatus(SysUser.STATUS_ACTIVE);
        return user;
    }

    private static Customer customer(long id, long ownerId, Long teamId, long version) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setTenantId(1L);
        customer.setCustomerNo(String.format("C%010d", id));
        customer.setDisplayName("客户" + id);
        customer.setOwnerStaffId(ownerId);
        customer.setTeamId(teamId);
        customer.setDeleted(false);
        customer.setVersion(version);
        return customer;
    }
}
