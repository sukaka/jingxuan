package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.complaint.application.ComplaintWorkflowService;
import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;
import com.whale.crm.customer.api.dto.CustomerTimelinePageResponse;
import com.whale.crm.customer.tier.application.CustomerTierCustomerService;
import com.whale.crm.fulfillment.application.AppointmentApplicationService;
import com.whale.crm.fulfillment.application.RedemptionApplicationService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.refund.application.RefundApplicationService;
import com.whale.crm.trading.application.OrderApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerTimelineApplicationServiceTest {

    private static final Instant BASE = Instant.parse("2026-11-21T10:00:00Z");

    private CustomerScopeSupport scopeSupport;
    private CustomerApplicationService customerService;
    private FollowupApplicationService followupService;
    private TaskApplicationService taskService;
    private OrderApplicationService orderService;
    private AppointmentApplicationService appointmentService;
    private RedemptionApplicationService redemptionService;
    private RefundApplicationService refundService;
    private CustomerTierCustomerService tierCustomerService;
    private ComplaintWorkflowService complaintService;
    private CustomerTimelineCursorCodec cursorCodec;
    private CustomerTimelineApplicationService service;

    @BeforeEach
    void setUp() {
        scopeSupport = mock(CustomerScopeSupport.class);
        customerService = mock(CustomerApplicationService.class);
        followupService = mock(FollowupApplicationService.class);
        taskService = mock(TaskApplicationService.class);
        orderService = mock(OrderApplicationService.class);
        appointmentService = mock(AppointmentApplicationService.class);
        redemptionService = mock(RedemptionApplicationService.class);
        refundService = mock(RefundApplicationService.class);
        tierCustomerService = mock(CustomerTierCustomerService.class);
        complaintService = mock(ComplaintWorkflowService.class);
        cursorCodec = new CustomerTimelineCursorCodec(new ObjectMapper().findAndRegisterModules());
        service = new CustomerTimelineApplicationService(
                scopeSupport,
                customerService,
                followupService,
                taskService,
                orderService,
                appointmentService,
                redemptionService,
                refundService,
                tierCustomerService,
                complaintService,
                cursorCodec);
    }

    @Test
    void passesSizePlusOneToAuthorizedBranchesAndSetsNextCursorOnlyWhenMoreItemsExist() {
        CurrentUser fullUser = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null, Set.of("ADMIN"),
                Set.of("followup:read", "task:read", "order:read", "appointment:read",
                        "redemption:read", "refund:read", "complaint:read"),
                Set.of());
        CustomerTimelineItemResponse item1 = CustomerTimelineItemResponse.of("ORDER", 1L, "ORDER",
                BASE.plusSeconds(3), "订单1", null, Map.of(), 1L);
        CustomerTimelineItemResponse item2 = CustomerTimelineItemResponse.of("TASK", 2L, "TASK",
                BASE.plusSeconds(2), "任务2", null, Map.of(), 1L);
        CustomerTimelineItemResponse item3 = CustomerTimelineItemResponse.of("FOLLOWUP", 3L, "FOLLOWUP",
                BASE.plusSeconds(1), "跟进3", null, Map.of(), 1L);

        when(customerService.timelineOwnership(1L, fullUser, 100L, null, 3)).thenReturn(List.of());
        when(followupService.timeline(1L, fullUser, 100L, null, 3)).thenReturn(List.of(item3));
        when(taskService.timeline(1L, fullUser, 100L, null, 3)).thenReturn(List.of(item2));
        when(orderService.timeline(1L, fullUser, 100L, null, 3)).thenReturn(List.of(item1));
        when(appointmentService.timeline(1L, fullUser, 100L, null, 3)).thenReturn(List.of());
        when(redemptionService.timeline(1L, fullUser, 100L, null, 3)).thenReturn(List.of());
        when(refundService.timeline(1L, fullUser, 100L, null, 3)).thenReturn(List.of());
        when(tierCustomerService.timeline(1L, fullUser, 100L, null, 3)).thenReturn(List.of());
        when(complaintService.timeline(fullUser, 100L, null, 3)).thenReturn(List.of());

        CustomerTimelinePageResponse pageWithMore = service.timeline(1L, fullUser, 100L, null, 2);

        assertThat(pageWithMore.items()).containsExactly(item1, item2);
        assertThat(pageWithMore.nextCursor()).isNotNull();
        verify(customerService).timelineOwnership(1L, fullUser, 100L, null, 3);
        verify(followupService).timeline(1L, fullUser, 100L, null, 3);
        verify(taskService).timeline(1L, fullUser, 100L, null, 3);
        verify(orderService).timeline(1L, fullUser, 100L, null, 3);
        verify(appointmentService).timeline(1L, fullUser, 100L, null, 3);
        verify(redemptionService).timeline(1L, fullUser, 100L, null, 3);
        verify(refundService).timeline(1L, fullUser, 100L, null, 3);
        verify(tierCustomerService).timeline(1L, fullUser, 100L, null, 3);
        verify(complaintService).timeline(fullUser, 100L, null, 3);

        when(followupService.timeline(1L, fullUser, 100L, null, 4)).thenReturn(List.of(item3));
        when(taskService.timeline(1L, fullUser, 100L, null, 4)).thenReturn(List.of(item2));
        when(orderService.timeline(1L, fullUser, 100L, null, 4)).thenReturn(List.of(item1));

        CustomerTimelinePageResponse exactPage = service.timeline(1L, fullUser, 100L, null, 3);
        verify(orderService).timeline(1L, fullUser, 100L, null, 4);
        assertThat(exactPage.items()).containsExactly(item1, item2, item3);
        assertThat(exactPage.nextCursor()).isNull();
    }

    @Test
    void skipsUnauthorizedBranchesWithoutCallingServices() {
        CurrentUser limitedUser = new CurrentUser(2L, 1L, "op", "运营", "EMPLOYEE", 1L, Set.of("OPERATION"),
                Set.of("order:read"), Set.of());
        when(customerService.timelineOwnership(1L, limitedUser, 100L, null, 6)).thenReturn(List.of());
        when(orderService.timeline(1L, limitedUser, 100L, null, 6)).thenReturn(List.of());
        when(tierCustomerService.timeline(1L, limitedUser, 100L, null, 6)).thenReturn(List.of());

        CustomerTimelinePageResponse response = service.timeline(1L, limitedUser, 100L, null, 5);

        assertThat(response.items()).isEmpty();
        assertThat(response.nextCursor()).isNull();
        verify(customerService).timelineOwnership(1L, limitedUser, 100L, null, 6);
        verify(orderService).timeline(1L, limitedUser, 100L, null, 6);
        verify(tierCustomerService).timeline(1L, limitedUser, 100L, null, 6);
        verify(followupService, never()).timeline(anyLong(), any(), anyLong(), any(), anyInt());
        verify(taskService, never()).timeline(anyLong(), any(), anyLong(), any(), anyInt());
        verify(appointmentService, never()).timeline(anyLong(), any(), anyLong(), any(), anyInt());
        verify(redemptionService, never()).timeline(anyLong(), any(), anyLong(), any(), anyInt());
        verify(refundService, never()).timeline(anyLong(), any(), anyLong(), any(), anyInt());
        verify(complaintService, never()).timeline(any(), anyLong(), any(), anyInt());
    }
}
