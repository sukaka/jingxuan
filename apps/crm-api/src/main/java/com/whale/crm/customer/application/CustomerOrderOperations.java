package com.whale.crm.customer.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.trading.domain.OrderStats;

import java.time.Instant;

public interface CustomerOrderOperations {

    void recordFirstOrder(long tenantId, CurrentUser user, long customerId, Instant paidAt);

    void reassignCustomer(long tenantId, long fromCustomerId, long toCustomerId);

    MergeOrderResourceSnapshot snapshotResources(long tenantId, long customerId);
    void lockResources(long tenantId, java.util.List<Long> orderIds, java.util.List<Long> entitlementIds);

    void reassignSpecific(long tenantId, java.util.List<Long> orderIds, java.util.List<Long> entitlementIds,
                          long fromCustomerId, long toCustomerId);

    OrderStats orderStats(long tenantId, long customerId);
}
