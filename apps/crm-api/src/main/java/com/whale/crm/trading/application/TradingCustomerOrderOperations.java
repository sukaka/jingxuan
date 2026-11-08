package com.whale.crm.trading.application;

import com.whale.crm.customer.application.CustomerApplicationService;
import com.whale.crm.customer.application.CustomerOrderOperations;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.trading.domain.OrderStats;
import com.whale.crm.trading.domain.TradingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class TradingCustomerOrderOperations implements CustomerOrderOperations {
    private final TradingRepository repository;
    private final CustomerApplicationService customerService;

    public TradingCustomerOrderOperations(TradingRepository repository, CustomerApplicationService customerService) {
        this.repository = repository;
        this.customerService = customerService;
    }

    @Override
    @Transactional
    public void recordFirstOrder(long tenantId, CurrentUser user, long customerId, Instant paidAt) {
        customerService.recordFirstOrder(tenantId, user, customerId, paidAt);
    }

    @Override
    @Transactional
    public void reassignCustomer(long tenantId, long fromCustomerId, long toCustomerId) {
        repository.reassignOrders(tenantId, fromCustomerId, toCustomerId);
        repository.reassignEntitlements(tenantId, fromCustomerId, toCustomerId);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderStats orderStats(long tenantId, long customerId) {
        return repository.orderStats(tenantId, customerId);
    }

    @Override
    @Transactional
    public com.whale.crm.customer.application.MergeOrderResourceSnapshot snapshotResources(long tenantId, long customerId) {
        return new com.whale.crm.customer.application.MergeOrderResourceSnapshot(
                repository.findOrderIdsByCustomer(tenantId, customerId),
                repository.findEntitlementIdsByCustomer(tenantId, customerId));
    }

    @Override
    @Transactional
    public void lockResources(long tenantId, java.util.List<Long> orderIds, java.util.List<Long> entitlementIds) {
        orderIds.stream().sorted().forEach(id -> repository.findOrderForUpdate(tenantId, id));
        entitlementIds.stream().sorted().forEach(id -> repository.findEntitlementForUpdate(tenantId, id));
    }

    @Override
    @Transactional
    public void reassignSpecific(long tenantId, java.util.List<Long> orderIds, java.util.List<Long> entitlementIds,
                                 long fromCustomerId, long toCustomerId) {
        if (!orderIds.isEmpty()) {
            repository.reassignSpecificOrders(tenantId, orderIds, fromCustomerId, toCustomerId);
        }
        if (!entitlementIds.isEmpty()) {
            repository.reassignSpecificEntitlements(tenantId, entitlementIds, fromCustomerId, toCustomerId);
        }
    }
}
