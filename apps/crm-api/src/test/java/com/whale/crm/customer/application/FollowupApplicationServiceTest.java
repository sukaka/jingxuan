package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.CreateFollowupRequest;
import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;
import com.whale.crm.customer.api.dto.FollowupItemResponse;
import com.whale.crm.customer.api.dto.UpdateFollowupRequest;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.Followup;
import com.whale.crm.customer.domain.FollowupRepository;
import com.whale.crm.followup.application.FollowupPolicyService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.application.OutboxService;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowupApplicationServiceTest {

    private static final Instant CONTACT_AT = Instant.parse("2026-11-21T08:00:00Z");
    private static final Instant NEXT_AT_1 = Instant.parse("2026-11-25T08:00:00Z");
    private static final Instant NEXT_AT_2 = Instant.parse("2026-11-28T08:00:00Z");
    private static final CurrentUser MANAGER = new CurrentUser(2L, 1L, "manager1", "管家1", "EMPLOYEE", 1L,
            Set.of("MANAGER"), Set.of("followup:write"), Set.of());

    private FollowupRepository followupRepository;
    private CustomerRepository customerRepository;
    private TaskApplicationService taskService;
    private FollowupPolicyService policyService;
    private IdempotencyService idempotencyService;
    private FollowupApplicationService service;

    @BeforeEach
    void setUp() {
        followupRepository = mock(FollowupRepository.class);
        customerRepository = mock(CustomerRepository.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);
        when(dataScopeService.scopeFor(MANAGER, DataScopeService.RESOURCE_CUSTOMER))
                .thenReturn(DataScope.self());
        CustomerScopeSupport scopeSupport = new CustomerScopeSupport(dataScopeService, customerRepository);
        AuditService auditService = mock(AuditService.class);
        taskService = mock(TaskApplicationService.class);
        policyService = mock(FollowupPolicyService.class);
        OutboxService outboxService = mock(OutboxService.class);
        idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.lookup(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new FollowupApplicationService(
                followupRepository,
                customerRepository,
                scopeSupport,
                auditService,
                taskService,
                policyService,
                outboxService,
                idempotencyService,
                new ObjectMapper().findAndRegisterModules(),
                txManager);
    }

    @Test
    void createLocksCustomerBeforeInsertingAndRefreshesNextFollowupAt() {
        Customer customer = customer(100L, 2L, 1L);
        when(customerRepository.findByIdForUpdate(1L, 100L)).thenReturn(Optional.of(customer));
        when(policyService.nextFollowupAt(eq(1L), any(), eq(CONTACT_AT), eq(NEXT_AT_1))).thenReturn(NEXT_AT_1);
        doAnswer(invocation -> {
            Followup arg = invocation.getArgument(0);
            arg.setId(501L);
            return null;
        }).when(followupRepository).insert(any(Followup.class));
        Followup persisted = followup(501L, 100L, 2L, CONTACT_AT, NEXT_AT_1, 0L);
        when(followupRepository.findById(1L, 501L)).thenReturn(Optional.of(persisted));

        FollowupItemResponse response = service.create(1L, MANAGER,
                new CreateFollowupRequest(100L, "WECOM", CONTACT_AT, "首次沟通", "热玛吉", 10000L, NEXT_AT_1, null),
                "idem-create-1");

        assertThat(response.id()).isEqualTo(501L);
        InOrder order = inOrder(customerRepository, idempotencyService, followupRepository, taskService);
        order.verify(customerRepository).findByIdForUpdate(1L, 100L);
        order.verify(idempotencyService).lookup(eq(1L), eq("followup.create"), eq("idem-create-1"), anyString());
        order.verify(followupRepository).insert(any(Followup.class));
        order.verify(customerRepository).updateLastFollowupAt(1L, 100L, CONTACT_AT);
        order.verify(customerRepository).refreshNextFollowupAt(1L, 100L);
        order.verify(taskService).syncFromFollowup(1L, MANAGER, persisted, customer);
    }

    @Test
    void updateLocksCustomerBeforeFollowupUpdateRefreshesOnlyWhenNextFollowupAtChangesAndRejectsReassignment() {
        Customer customer = customer(100L, 2L, 1L);
        Followup initial = followup(501L, 100L, 2L, CONTACT_AT, NEXT_AT_1, 0L);
        Followup afterSameNext = followup(501L, 100L, 2L, CONTACT_AT, NEXT_AT_1, 1L);
        when(followupRepository.findById(1L, 501L)).thenReturn(Optional.of(initial), Optional.of(initial),
                Optional.of(afterSameNext));
        when(customerRepository.findByIdForUpdate(1L, 100L)).thenReturn(Optional.of(customer));
        when(followupRepository.update(any(Followup.class))).thenReturn(1);

        FollowupItemResponse unchangedRes = service.update(1L, MANAGER, 501L,
                new UpdateFollowupRequest(null, null, "补充说明", null, null, null, null, 0L));

        assertThat(unchangedRes.version()).isEqualTo(1L);
        verify(customerRepository, never()).refreshNextFollowupAt(anyLong(), anyLong());
        verify(taskService, never()).syncFromFollowup(anyLong(), any(), any(), any());

        Followup current = followup(501L, 100L, 2L, CONTACT_AT, NEXT_AT_1, 1L);
        Followup afterChangedNext = followup(501L, 100L, 2L, CONTACT_AT, NEXT_AT_2, 2L);
        when(followupRepository.findById(1L, 501L)).thenReturn(Optional.of(current), Optional.of(current),
                Optional.of(afterChangedNext));

        FollowupItemResponse changedRes = service.update(1L, MANAGER, 501L,
                new UpdateFollowupRequest(null, null, null, null, null, NEXT_AT_2, null, 1L));

        assertThat(changedRes.nextFollowupAt()).isEqualTo(NEXT_AT_2);
        InOrder order = inOrder(customerRepository, followupRepository, taskService);
        order.verify(customerRepository).findByIdForUpdate(1L, 100L);
        order.verify(followupRepository).update(any(Followup.class));
        order.verify(customerRepository).refreshNextFollowupAt(1L, 100L);
        order.verify(taskService).syncFromFollowup(1L, MANAGER, afterChangedNext, customer);

        Followup reassigned = followup(501L, 999L, 2L, CONTACT_AT, NEXT_AT_2, 2L);
        when(followupRepository.findById(1L, 501L)).thenReturn(Optional.of(current), Optional.of(reassigned));
        assertThatThrownBy(() -> service.update(1L, MANAGER, 501L,
                new UpdateFollowupRequest(null, null, "并发", null, null, NEXT_AT_1, null, 2L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void deleteLocksCustomerBeforeSoftDeleteRefreshesNextFollowupAtAndRejectsReassignment() {
        Customer customer = customer(100L, 2L, 1L);
        Followup existing = followup(501L, 100L, 2L, CONTACT_AT, NEXT_AT_1, 0L);
        when(followupRepository.findById(1L, 501L)).thenReturn(Optional.of(existing), Optional.of(existing));
        when(customerRepository.findByIdForUpdate(1L, 100L)).thenReturn(Optional.of(customer));
        when(followupRepository.update(any(Followup.class))).thenReturn(1);

        service.delete(1L, MANAGER, 501L, 0L);

        InOrder order = inOrder(customerRepository, followupRepository);
        order.verify(customerRepository).findByIdForUpdate(1L, 100L);
        order.verify(followupRepository).update(any(Followup.class));
        order.verify(customerRepository).refreshNextFollowupAt(1L, 100L);

        Followup initialBeforeReassign = followup(501L, 100L, 2L, CONTACT_AT, NEXT_AT_1, 0L);
        Followup reassigned = followup(501L, 999L, 2L, CONTACT_AT, NEXT_AT_1, 0L);
        when(followupRepository.findById(1L, 501L)).thenReturn(Optional.of(initialBeforeReassign), Optional.of(reassigned));
        assertThatThrownBy(() -> service.delete(1L, MANAGER, 501L, 0L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void timelineTreatsUnicodeAndControlWhitespaceIntentAndBlockerAsBlank() {
        Customer customer = customer(100L, 2L, 1L);
        when(customerRepository.findById(1L, 100L)).thenReturn(Optional.of(customer));
        when(followupRepository.findTimelineEvents(anyLong(), any(), any(), anyLong(), anyLong(),
                any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        new FollowupRepository.FollowupTimelineRow("INTENT", 1L, "PHONE", CONTACT_AT, "跟进1",
                                "\u00A0\u3000\t\n\r\u0085", 0L, null, null, 2L, CONTACT_AT, CONTACT_AT),
                        new FollowupRepository.FollowupTimelineRow("BLOCKER", 1L, "PHONE", CONTACT_AT, "跟进1",
                                null, 0L, null, "\u00A0\u2007\u202F\u3000", 2L, CONTACT_AT, CONTACT_AT),
                        new FollowupRepository.FollowupTimelineRow("FOLLOWUP", 1L, "PHONE", CONTACT_AT, "跟进1",
                                null, 0L, null, null, 2L, CONTACT_AT, CONTACT_AT),
                        new FollowupRepository.FollowupTimelineRow("INTENT", 2L, "PHONE", CONTACT_AT, "跟进2",
                                "  热玛吉  ", 100L, null, null, 2L, CONTACT_AT, CONTACT_AT)));

        List<CustomerTimelineItemResponse> items = service.timeline(1L, MANAGER, 100L, null, 10);

        assertThat(items).extracting(CustomerTimelineItemResponse::eventKey)
                .containsExactly("00000000000000000001:FOLLOWUP", "00000000000000000002:INTENT");
    }

    private static Customer customer(long id, long ownerStaffId, long teamId) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setTenantId(1L);
        customer.setOwnerStaffId(ownerStaffId);
        customer.setTeamId(teamId);
        customer.setIntentLevel("HIGH");
        customer.setVersion(0L);
        customer.setDeleted(false);
        return customer;
    }

    private static Followup followup(long id, long customerId, long createdBy, Instant contactAt,
                                     Instant nextFollowupAt, long version) {
        Followup followup = new Followup();
        followup.setId(id);
        followup.setTenantId(1L);
        followup.setCustomerId(customerId);
        followup.setOwnerStaffId(createdBy);
        followup.setChannelCode("WECOM");
        followup.setContactAt(contactAt);
        followup.setSummary("跟进");
        followup.setEstimatedAmountFen(0L);
        followup.setNextFollowupAt(nextFollowupAt);
        followup.setCreatedBy(createdBy);
        followup.setCreatedAt(contactAt);
        followup.setVersion(version);
        followup.setDeleted(false);
        return followup;
    }
}
