package com.whale.crm.trading.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementCorrectionView;
import com.whale.crm.trading.domain.FulfillmentEntitlementView;
import com.whale.crm.trading.domain.LockedEntitlementBatch;
import com.whale.crm.trading.domain.Order;

import java.time.Instant;
import java.util.List;

public interface FulfillmentTradingOperations {
    List<FulfillmentEntitlementView> appointmentOptions(long tenantId, CurrentUser user, long customerId);

    List<FulfillmentEntitlementView> appointmentOptionsForInstitution(long tenantId, long customerId,
                                                                      long institutionId, List<Long> entitlementIds);

    LockedEntitlementBatch requireAppointmentEntitlements(long tenantId, List<AppointmentLockCommand> commands);

    FulfillmentEntitlementView requireAppointmentEntitlement(long tenantId, long entitlementId, long customerId,
                                                             long institutionId, int count, Instant scheduledAt);

    LockedEntitlementBatch lockRedeemables(long tenantId, List<RedemptionLockCommand> commands);

    FulfillmentEntitlementView lockRedeemable(long tenantId, long entitlementId, long customerId,
                                              long institutionId, long projectId, int count, Instant serviceAt);

    LockedEntitlementBatch lockReversibles(long tenantId, List<ReversalLockCommand> commands);

    FulfillmentEntitlementView lockReversible(long tenantId, long entitlementId, long customerId, long projectId,
                                              String originalSourceRef, int originalCount);

    List<Entitlement> appendRedemptions(long tenantId, long actorUserId, LockedEntitlementBatch locked,
                                        List<RedemptionAppendCommand> commands);

    List<Entitlement> appendReversals(long tenantId, long actorUserId, LockedEntitlementBatch locked,
                                      List<ReversalAppendCommand> commands);

    void recalculateOrderStatusAfterRefund(long tenantId, Long actorUserId, Order order, long refundId);

    List<EntitlementCorrectionView> corrections(long tenantId, long reversalId);

    record AppointmentLockCommand(long entitlementId, long customerId, long institutionId,
                                        int count, Instant scheduledAt) {
    }

    record RedemptionLockCommand(long entitlementId, long customerId, long institutionId,
                                       long projectId, int count, Instant serviceAt) {
    }

    record RedemptionAppendCommand(long entitlementId, int count, String sourceRef, String idempotencyKey) {
    }

    record ReversalLockCommand(long entitlementId, long customerId, long projectId,
                                     String originalSourceRef, int originalCount) {
    }

    record ReversalAppendCommand(long entitlementId, int count, String sourceRef, String idempotencyKey) {
    }
}
