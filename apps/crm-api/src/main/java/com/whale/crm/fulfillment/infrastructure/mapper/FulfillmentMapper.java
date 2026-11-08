package com.whale.crm.fulfillment.infrastructure.mapper;

import com.whale.crm.fulfillment.domain.Appointment;
import com.whale.crm.fulfillment.domain.AppointmentHistory;
import com.whale.crm.fulfillment.domain.AppointmentItem;
import com.whale.crm.fulfillment.domain.FulfillmentScope;
import com.whale.crm.fulfillment.domain.Redemption;
import com.whale.crm.fulfillment.domain.RedemptionLine;
import com.whale.crm.fulfillment.domain.RedemptionReversal;
import com.whale.crm.fulfillment.domain.RedemptionReversalHistory;
import com.whale.crm.fulfillment.domain.RedemptionStatusHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface FulfillmentMapper {
    @org.apache.ibatis.annotations.Select("SELECT id FROM crm_appointment_item WHERE tenant_id=#{tenantId} AND entitlement_id=#{id} LIMIT 1 FOR UPDATE")
    Long lockAppointmentReference(@Param("tenantId") long tenantId, @Param("id") long id);
    @org.apache.ibatis.annotations.Select("SELECT id FROM crm_redemption_line WHERE tenant_id=#{tenantId} AND entitlement_id=#{id} LIMIT 1 FOR UPDATE")
    Long lockRedemptionReference(@Param("tenantId") long tenantId, @Param("id") long id);
    void insertAppointment(@Param("tenantId") long tenantId, @Param("entity") Appointment entity);
    Appointment selectAppointmentByNo(@Param("tenantId") long tenantId, @Param("appointmentNo") String appointmentNo);
    int backfillAppointmentNo(@Param("tenantId") long tenantId, @Param("id") long id,
                              @Param("appointmentNo") String appointmentNo);
    Appointment selectAppointment(@Param("tenantId") long tenantId, @Param("id") long id);
    Appointment selectAppointmentForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    List<Appointment> searchAppointments(@Param("tenantId") long tenantId, @Param("scope") FulfillmentScope scope,
                                         @Param("customerId") Long customerId, @Param("institutionId") Long institutionId,
                                         @Param("status") String status, @Param("offset") int offset, @Param("size") int size);
    long countAppointments(@Param("tenantId") long tenantId, @Param("scope") FulfillmentScope scope,
                           @Param("customerId") Long customerId, @Param("institutionId") Long institutionId,
                           @Param("status") String status);
    int updateAppointmentStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                                @Param("expectedVersion") long expectedVersion,
                                @Param("expectedStatus") String expectedStatus, @Param("toStatus") String toStatus);
    void insertAppointmentItem(@Param("tenantId") long tenantId, @Param("entity") AppointmentItem entity);
    List<AppointmentItem> selectAppointmentItems(@Param("tenantId") long tenantId,
                                                 @Param("appointmentId") long appointmentId);
    void insertAppointmentHistory(@Param("tenantId") long tenantId, @Param("entity") AppointmentHistory entity);
    List<AppointmentHistory> selectAppointmentHistory(@Param("tenantId") long tenantId,
                                                      @Param("appointmentId") long appointmentId);

    void insertRedemption(@Param("tenantId") long tenantId, @Param("entity") Redemption entity);
    Redemption selectRedemptionByKey(@Param("tenantId") long tenantId, @Param("idempotencyKey") String idempotencyKey);
    Redemption selectRedemption(@Param("tenantId") long tenantId, @Param("id") long id);
    Redemption selectRedemptionForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    List<Redemption> selectRedemptionsByAppointment(@Param("tenantId") long tenantId,
                                                    @Param("appointmentId") long appointmentId);
    List<Redemption> searchRedemptions(@Param("tenantId") long tenantId, @Param("scope") FulfillmentScope scope,
                                       @Param("customerId") Long customerId, @Param("institutionId") Long institutionId,
                                       @Param("projectId") Long projectId, @Param("status") String status,
                                       @Param("offset") int offset, @Param("size") int size);
    long countRedemptions(@Param("tenantId") long tenantId, @Param("scope") FulfillmentScope scope,
                          @Param("customerId") Long customerId, @Param("institutionId") Long institutionId,
                          @Param("projectId") Long projectId, @Param("status") String status);
    void insertRedemptionLine(@Param("tenantId") long tenantId, @Param("entity") RedemptionLine entity);
    List<RedemptionLine> selectRedemptionLines(@Param("tenantId") long tenantId,
                                               @Param("redemptionId") long redemptionId);
    List<RedemptionLine> selectPostedLinesByAppointment(@Param("tenantId") long tenantId,
                                                        @Param("appointmentId") long appointmentId);
    void insertRedemptionHistory(@Param("tenantId") long tenantId, @Param("entity") RedemptionStatusHistory entity);
    List<RedemptionStatusHistory> selectRedemptionHistory(@Param("tenantId") long tenantId,
                                                          @Param("redemptionId") long redemptionId);

    void insertReversal(@Param("tenantId") long tenantId, @Param("entity") RedemptionReversal entity);
    RedemptionReversal selectReversalByRequestKey(@Param("tenantId") long tenantId,
                                                  @Param("requestKey") String requestKey);
    RedemptionReversal selectReversal(@Param("tenantId") long tenantId, @Param("id") long id);
    RedemptionReversal selectReversalForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    RedemptionReversal selectActiveReversalByRedemption(@Param("tenantId") long tenantId,
                                                        @Param("redemptionId") long redemptionId);
    List<RedemptionReversal> searchReversals(@Param("tenantId") long tenantId, @Param("scope") FulfillmentScope scope,
                                             @Param("customerId") Long customerId, @Param("institutionId") Long institutionId,
                                             @Param("status") String status, @Param("offset") int offset, @Param("size") int size);
    long countReversals(@Param("tenantId") long tenantId, @Param("scope") FulfillmentScope scope,
                        @Param("customerId") Long customerId, @Param("institutionId") Long institutionId,
                        @Param("status") String status);
    int updateReversalStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                             @Param("expectedVersion") long expectedVersion,
                             @Param("expectedStatus") String expectedStatus, @Param("toStatus") String toStatus,
                             @Param("reviewedBy") long reviewedBy, @Param("reviewedAt") Instant reviewedAt,
                             @Param("reviewReason") String reviewReason, @Param("reviewKey") String reviewKey,
                             @Param("activeRedemptionId") Long activeRedemptionId);
    void insertReversalHistory(@Param("tenantId") long tenantId, @Param("entity") RedemptionReversalHistory entity);
    List<RedemptionReversalHistory> selectReversalHistory(@Param("tenantId") long tenantId,
                                                          @Param("reversalId") long reversalId);
}
