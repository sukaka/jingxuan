package com.whale.crm.integration.wecom.sidebar.application;

import com.whale.crm.customer.api.dto.CustomerDetailResponse;
import com.whale.crm.customer.application.CustomerApplicationService;
import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarContextResponse;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.application.EntitlementApplicationService;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.domain.EntitlementSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WecomSidebarContextService {
    private final WecomRepository repository;
    private final WecomOfficialProperties properties;
    private final CustomerApplicationService customerService;
    private final OrderApplicationService orderService;
    private final EntitlementApplicationService entitlementService;

    public WecomSidebarContextService(WecomRepository repository, WecomOfficialProperties properties,
                                      CustomerApplicationService customerService, OrderApplicationService orderService,
                                      EntitlementApplicationService entitlementService) {
        this.repository = repository;
        this.properties = properties;
        this.customerService = customerService;
        this.orderService = orderService;
        this.entitlementService = entitlementService;
    }

    @Transactional(readOnly = true)
    public WecomSidebarContextResponse context(CurrentUser user, String externalUserid) {
        String corpId = properties.corpId();
        WecomSidebarContextResponse.Employee employee = new WecomSidebarContextResponse.Employee(
                user.userId(), user.displayName());
        if (externalUserid == null || externalUserid.isBlank()) {
            return new WecomSidebarContextResponse("WAITING_CONTEXT", corpId, null, employee, null,
                    new WecomSidebarContextResponse.Summary(null, null, null, null));
        }
        List<WecomContact> contacts = repository.findContactsByExternalUserid(user.tenantId(), corpId,
                externalUserid.strip());
        List<Long> customerIds = contacts.stream()
                .filter(contact -> "ACTIVE".equals(contact.followStatus()))
                .map(WecomContact::customerId).filter(java.util.Objects::nonNull).distinct().toList();
        if (customerIds.isEmpty()) {
            return new WecomSidebarContextResponse("CUSTOMER_UNLINKED", corpId, externalUserid.strip(), employee,
                    null, new WecomSidebarContextResponse.Summary(null, null, null, null));
        }
        if (customerIds.size() != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前企微客户关联了多个 CRM 客户，请人工核对");
        }
        long customerId = customerIds.getFirst();
        CustomerDetailResponse customer = customerService.detail(user.tenantId(), user, customerId);
        Long totalOrders = user.permissions().contains("order:read")
                ? orderService.search(user.tenantId(), user, customerId, null, 1, 1).total() : null;
        Long remainingPackages = null;
        Long remainingCount = null;
        if (user.permissions().contains("entitlement:read")) {
            EntitlementSummary entitlements = entitlementService.summary(user.tenantId(), user, customerId);
            remainingCount = entitlements.availableCount();
            remainingPackages = entitlementService.search(user.tenantId(), user, customerId, null,
                    null, true, 1, 1).total();
        }
        return new WecomSidebarContextResponse("READY", corpId, externalUserid.strip(), employee, customer,
                new WecomSidebarContextResponse.Summary(totalOrders, remainingPackages, remainingCount,
                        customer.lastFollowupAt()));
    }
}
