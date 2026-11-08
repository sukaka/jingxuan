package com.whale.crm.refund.application;

import com.whale.crm.refund.infrastructure.mapper.RefundMapper;
import org.springframework.stereotype.Service;

@Service
public class MergeRefundReferenceService {
    private final RefundMapper mapper;

    public MergeRefundReferenceService(RefundMapper mapper) { this.mapper = mapper; }

    public boolean hasOrderReference(long tenantId, long orderId) {
        return mapper.lockOrderReference(tenantId, orderId) != null;
    }

    public boolean hasEntitlementReference(long tenantId, long entitlementId) {
        return mapper.lockEntitlementReference(tenantId, entitlementId) != null;
    }
}
