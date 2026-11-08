package com.whale.crm.fulfillment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FulfillmentRepository {
    void insertAppointment(Appointment appointment);
    Optional<Appointment> findAppointmentByNo(long tenantId, String appointmentNo);
    int backfillAppointmentNo(long tenantId, long id, String appointmentNo);
    Optional<Appointment> findAppointment(long tenantId, long id);
    Optional<Appointment> findAppointmentForUpdate(long tenantId, long id);
    List<Appointment> searchAppointments(long tenantId, FulfillmentScope scope, Long customerId,
                                         Long institutionId, String status, int offset, int size);
    long countAppointments(long tenantId, FulfillmentScope scope, Long customerId, Long institutionId, String status);
    int updateAppointmentStatus(long tenantId, long id, long expectedVersion, String expectedStatus, String toStatus);
    void insertAppointmentItem(AppointmentItem item);
    List<AppointmentItem> findAppointmentItems(long tenantId, long appointmentId);
    void insertAppointmentHistory(AppointmentHistory history);
    List<AppointmentHistory> findAppointmentHistory(long tenantId, long appointmentId);

    void insertRedemption(Redemption redemption);
    Optional<Redemption> findRedemptionByKey(long tenantId, String idempotencyKey);
    Optional<Redemption> findRedemption(long tenantId, long id);
    Optional<Redemption> findRedemptionForUpdate(long tenantId, long id);
    List<Redemption> findRedemptionsByAppointment(long tenantId, long appointmentId);
    List<Redemption> searchRedemptions(long tenantId, FulfillmentScope scope, Long customerId,
                                       Long institutionId, Long projectId, String status, int offset, int size);
    long countRedemptions(long tenantId, FulfillmentScope scope, Long customerId, Long institutionId,
                          Long projectId, String status);
    void insertRedemptionLine(RedemptionLine line);
    List<RedemptionLine> findRedemptionLines(long tenantId, long redemptionId);
    List<RedemptionLine> findPostedLinesByAppointment(long tenantId, long appointmentId);
    void insertRedemptionHistory(RedemptionStatusHistory history);
    List<RedemptionStatusHistory> findRedemptionHistory(long tenantId, long redemptionId);

    void insertReversal(RedemptionReversal reversal);
    Optional<RedemptionReversal> findReversalByRequestKey(long tenantId, String requestKey);
    Optional<RedemptionReversal> findReversal(long tenantId, long id);
    Optional<RedemptionReversal> findReversalForUpdate(long tenantId, long id);
    Optional<RedemptionReversal> findActiveReversalByRedemption(long tenantId, long redemptionId);
    List<RedemptionReversal> searchReversals(long tenantId, FulfillmentScope scope, Long customerId,
                                             Long institutionId, String status, int offset, int size);
    long countReversals(long tenantId, FulfillmentScope scope, Long customerId, Long institutionId, String status);
    int updateReversalStatus(long tenantId, long id, long expectedVersion, String expectedStatus, String toStatus,
                             long reviewedBy, Instant reviewedAt, String reviewReason, String reviewKey,
                             Long activeRedemptionId);
    void insertReversalHistory(RedemptionReversalHistory history);
    List<RedemptionReversalHistory> findReversalHistory(long tenantId, long reversalId);
}
