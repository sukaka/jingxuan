package com.whale.crm.customer.application;

import com.whale.crm.fulfillment.application.MergeFulfillmentReferenceService;
import com.whale.crm.refund.application.MergeRefundReferenceService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MergeReferenceGuard {
    private final CustomerOrderOperations orders;
    private final MergeFulfillmentReferenceService fulfillment;
    private final MergeRefundReferenceService refunds;

    public MergeReferenceGuard(CustomerOrderOperations orders, MergeFulfillmentReferenceService fulfillment,
                               MergeRefundReferenceService refunds) {
        this.orders = orders;
        this.fulfillment = fulfillment;
        this.refunds = refunds;
    }

    public void requireUnreferenced(long tenantId, List<Long> orderIds, List<Long> entitlementIds) {
        orders.lockResources(tenantId, orderIds, entitlementIds);
        for (long id : orderIds) {
            if (refunds.hasOrderReference(tenantId, id)) {
                conflict();
            }
        }
        for (long id : entitlementIds) {
            if (fulfillment.hasReference(tenantId, id) || refunds.hasEntitlementReference(tenantId, id)) {
                conflict();
            }
        }
    }

    private void conflict() {
        throw new BusinessException(ErrorCode.CONFLICT, "订单或权益存在预约、核销或退款关联，无法自动迁移，请人工处理");
    }
}
