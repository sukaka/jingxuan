package com.whale.crm.fulfillment.application;

import com.whale.crm.fulfillment.infrastructure.mapper.FulfillmentMapper;
import org.springframework.stereotype.Service;

@Service
public class MergeFulfillmentReferenceService {
    private final FulfillmentMapper mapper;

    public MergeFulfillmentReferenceService(FulfillmentMapper mapper) { this.mapper = mapper; }

    public boolean hasReference(long tenantId, long entitlementId) {
        return mapper.lockAppointmentReference(tenantId, entitlementId) != null
                || mapper.lockRedemptionReference(tenantId, entitlementId) != null;
    }
}
