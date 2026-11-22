package com.whale.crm.customer.application;

import com.whale.crm.complaint.application.ComplaintWorkflowService;
import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;
import com.whale.crm.customer.api.dto.CustomerTimelinePageResponse;
import com.whale.crm.customer.tier.application.CustomerTierCustomerService;
import com.whale.crm.fulfillment.application.AppointmentApplicationService;
import com.whale.crm.fulfillment.application.RedemptionApplicationService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.refund.application.RefundApplicationService;
import com.whale.crm.trading.application.OrderApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class CustomerTimelineApplicationService {
    private final CustomerScopeSupport scopeSupport;
    private final CustomerApplicationService customerService;
    private final FollowupApplicationService followupService;
    private final TaskApplicationService taskService;
    private final OrderApplicationService orderService;
    private final AppointmentApplicationService appointmentService;
    private final RedemptionApplicationService redemptionService;
    private final RefundApplicationService refundService;
    private final CustomerTierCustomerService tierCustomerService;
    private final ComplaintWorkflowService complaintService;
    private final CustomerTimelineCursorCodec cursorCodec;

    public CustomerTimelineApplicationService(CustomerScopeSupport scopeSupport,
                                              CustomerApplicationService customerService,
                                              FollowupApplicationService followupService,
                                              TaskApplicationService taskService,
                                              OrderApplicationService orderService,
                                              AppointmentApplicationService appointmentService,
                                              RedemptionApplicationService redemptionService,
                                              RefundApplicationService refundService,
                                              CustomerTierCustomerService tierCustomerService,
                                              ComplaintWorkflowService complaintService,
                                              CustomerTimelineCursorCodec cursorCodec) {
        this.scopeSupport = scopeSupport;
        this.customerService = customerService;
        this.followupService = followupService;
        this.taskService = taskService;
        this.orderService = orderService;
        this.appointmentService = appointmentService;
        this.redemptionService = redemptionService;
        this.refundService = refundService;
        this.tierCustomerService = tierCustomerService;
        this.complaintService = complaintService;
        this.cursorCodec = cursorCodec;
    }

    @Transactional(readOnly = true)
    public CustomerTimelinePageResponse timeline(long tenantId, CurrentUser user, long customerId,
                                                  String encodedCursor, int size) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        CustomerTimelineCursor cursor = cursorCodec.decode(encodedCursor, customerId);
        List<CustomerTimelineItemResponse> events = new ArrayList<>();
        events.addAll(customerService.timelineOwnership(tenantId, user, customerId, cursor));
        if (user.permissions().contains("followup:read")) {
            events.addAll(followupService.timeline(tenantId, user, customerId, cursor));
        }
        if (user.permissions().contains("task:read")) {
            events.addAll(taskService.timeline(tenantId, user, customerId, cursor));
        }
        if (user.permissions().contains("order:read")) {
            events.addAll(orderService.timeline(tenantId, user, customerId, cursor));
        }
        if (user.permissions().contains("appointment:read")) {
            events.addAll(appointmentService.timeline(tenantId, user, customerId, cursor));
        }
        if (user.permissions().contains("redemption:read")) {
            events.addAll(redemptionService.timeline(tenantId, user, customerId, cursor));
        }
        if (user.permissions().contains("refund:read")) {
            events.addAll(refundService.timeline(tenantId, user, customerId, cursor));
        }
        events.addAll(tierCustomerService.timeline(tenantId, user, customerId, cursor));
        if (user.permissions().contains("complaint:read")) {
            events.addAll(complaintService.timeline(user, customerId, cursor));
        }
        List<CustomerTimelineItemResponse> ordered = events.stream().sorted(CustomerTimelineCursor.ORDER).toList();
        List<CustomerTimelineItemResponse> items = ordered.stream().limit(size).toList();
        String nextCursor = ordered.size() > size
                ? cursorCodec.encode(items.getLast(), customerId) : null;
        return new CustomerTimelinePageResponse(items, nextCursor);
    }
}
