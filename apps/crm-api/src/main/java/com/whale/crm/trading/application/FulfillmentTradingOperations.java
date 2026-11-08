package com.whale.crm.trading.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementCorrectionView;
import com.whale.crm.trading.domain.FulfillmentEntitlementView;

import java.time.Instant;
import java.util.List;

public interface FulfillmentTradingOperations {
    List<FulfillmentEntitlementView> appointmentOptions(long tenantId, CurrentUser user, long customerId);

    List<FulfillmentEntitlementView> appointmentOptionsForInstitution(long tenantId, long customerId,
                                                                      long institutionId, List<Long> entitlementIds);

    FulfillmentEntitlementView requireAppointmentEntitlement(long tenantId, long entitlementId, long customerId,
                                                             long institutionId, int count, Instant scheduledAt);

    FulfillmentEntitlementView lockRedeemable(long tenantId, long entitlementId, long customerId,
                                              long institutionId, long projectId, int count, Instant serviceAt);

    FulfillmentEntitlementView lockReversible(long tenantId, long entitlementId, long customerId, long projectId,
                                              String originalSourceRef, int originalCount);

    Entitlement appendRedemption(long tenantId, long actorUserId, FulfillmentEntitlementView locked,
                                 int count, String sourceRef, String idempotencyKey);

    Entitlement appendReversal(long tenantId, long actorUserId, FulfillmentEntitlementView locked,
                               int count, String sourceRef, String idempotencyKey);

    List<EntitlementCorrectionView> corrections(long tenantId, long reversalId);
}
