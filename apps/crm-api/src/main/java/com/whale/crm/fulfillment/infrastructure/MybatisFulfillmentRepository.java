package com.whale.crm.fulfillment.infrastructure;

import com.whale.crm.fulfillment.domain.Appointment;
import com.whale.crm.fulfillment.domain.AppointmentHistory;
import com.whale.crm.fulfillment.domain.AppointmentItem;
import com.whale.crm.fulfillment.domain.FulfillmentRepository;
import com.whale.crm.fulfillment.domain.FulfillmentScope;
import com.whale.crm.fulfillment.domain.Redemption;
import com.whale.crm.fulfillment.domain.RedemptionLine;
import com.whale.crm.fulfillment.domain.RedemptionReversal;
import com.whale.crm.fulfillment.domain.RedemptionReversalHistory;
import com.whale.crm.fulfillment.domain.RedemptionStatusHistory;
import com.whale.crm.fulfillment.infrastructure.mapper.FulfillmentMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisFulfillmentRepository implements FulfillmentRepository {
    private final FulfillmentMapper mapper;

    public MybatisFulfillmentRepository(FulfillmentMapper mapper) {
        this.mapper = mapper;
    }

    public void insertAppointment(Appointment entity) { mapper.insertAppointment(com.whale.crm.platform.context.TenantContext.currentTenantId(), entity); }
    public Optional<Appointment> findAppointmentByNo(long tenantId, String no) { return Optional.ofNullable(mapper.selectAppointmentByNo(tenantId, no)); }
    public int backfillAppointmentNo(long tenantId, long id, String no) { return mapper.backfillAppointmentNo(tenantId, id, no); }
    public Optional<Appointment> findAppointment(long tenantId, long id) { return Optional.ofNullable(mapper.selectAppointment(tenantId, id)); }
    public Optional<Appointment> findAppointmentForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectAppointmentForUpdate(tenantId, id)); }
    public List<Appointment> searchAppointments(long tenantId, FulfillmentScope scope, Long customerId, Long institutionId,
                                                String status, int offset, int size) {
        return mapper.searchAppointments(tenantId, scope, customerId, institutionId, status, offset, size);
    }
    public long countAppointments(long tenantId, FulfillmentScope scope, Long customerId, Long institutionId, String status) {
        return mapper.countAppointments(tenantId, scope, customerId, institutionId, status);
    }
    public int updateAppointmentStatus(long tenantId, long id, long version, String from, String to) {
        return mapper.updateAppointmentStatus(tenantId, id, version, from, to);
    }
    public void insertAppointmentItem(AppointmentItem entity) { mapper.insertAppointmentItem(com.whale.crm.platform.context.TenantContext.currentTenantId(), entity); }
    public List<AppointmentItem> findAppointmentItems(long tenantId, long id) { return mapper.selectAppointmentItems(tenantId, id); }
    public void insertAppointmentHistory(AppointmentHistory entity) { mapper.insertAppointmentHistory(com.whale.crm.platform.context.TenantContext.currentTenantId(), entity); }
    public List<AppointmentHistory> findAppointmentHistory(long tenantId, long id) { return mapper.selectAppointmentHistory(tenantId, id); }

    public void insertRedemption(Redemption entity) { mapper.insertRedemption(com.whale.crm.platform.context.TenantContext.currentTenantId(), entity); }
    public Optional<Redemption> findRedemptionByKey(long tenantId, String key) { return Optional.ofNullable(mapper.selectRedemptionByKey(tenantId, key)); }
    public Optional<Redemption> findRedemption(long tenantId, long id) { return Optional.ofNullable(mapper.selectRedemption(tenantId, id)); }
    public Optional<Redemption> findRedemptionForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectRedemptionForUpdate(tenantId, id)); }
    public List<Redemption> findRedemptionsByAppointment(long tenantId, long id) { return mapper.selectRedemptionsByAppointment(tenantId, id); }
    public List<Redemption> searchRedemptions(long tenantId, FulfillmentScope scope, Long customerId, Long institutionId,
                                              Long projectId, String status, int offset, int size) {
        return mapper.searchRedemptions(tenantId, scope, customerId, institutionId, projectId, status, offset, size);
    }
    public long countRedemptions(long tenantId, FulfillmentScope scope, Long customerId, Long institutionId,
                                 Long projectId, String status) {
        return mapper.countRedemptions(tenantId, scope, customerId, institutionId, projectId, status);
    }
    public void insertRedemptionLine(RedemptionLine entity) { mapper.insertRedemptionLine(com.whale.crm.platform.context.TenantContext.currentTenantId(), entity); }
    public List<RedemptionLine> findRedemptionLines(long tenantId, long id) { return mapper.selectRedemptionLines(tenantId, id); }
    public List<RedemptionLine> findPostedLinesByAppointment(long tenantId, long id) { return mapper.selectPostedLinesByAppointment(tenantId, id); }
    public void insertRedemptionHistory(RedemptionStatusHistory entity) { mapper.insertRedemptionHistory(com.whale.crm.platform.context.TenantContext.currentTenantId(), entity); }
    public List<RedemptionStatusHistory> findRedemptionHistory(long tenantId, long id) { return mapper.selectRedemptionHistory(tenantId, id); }

    public void insertReversal(RedemptionReversal entity) { mapper.insertReversal(com.whale.crm.platform.context.TenantContext.currentTenantId(), entity); }
    public Optional<RedemptionReversal> findReversalByRequestKey(long tenantId, String key) { return Optional.ofNullable(mapper.selectReversalByRequestKey(tenantId, key)); }
    public Optional<RedemptionReversal> findReversal(long tenantId, long id) { return Optional.ofNullable(mapper.selectReversal(tenantId, id)); }
    public Optional<RedemptionReversal> findReversalForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectReversalForUpdate(tenantId, id)); }
    public Optional<RedemptionReversal> findActiveReversalByRedemption(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectActiveReversalByRedemption(tenantId, id));
    }
    public List<RedemptionReversal> searchReversals(long tenantId, FulfillmentScope scope, Long customerId, Long institutionId,
                                                    String status, int offset, int size) {
        return mapper.searchReversals(tenantId, scope, customerId, institutionId, status, offset, size);
    }
    public long countReversals(long tenantId, FulfillmentScope scope, Long customerId, Long institutionId, String status) {
        return mapper.countReversals(tenantId, scope, customerId, institutionId, status);
    }
    public int updateReversalStatus(long tenantId, long id, long version, String from, String to, long reviewedBy,
                                    Instant reviewedAt, String reviewReason, String reviewKey, Long activeRedemptionId) {
        return mapper.updateReversalStatus(tenantId, id, version, from, to, reviewedBy, reviewedAt,
                reviewReason, reviewKey, activeRedemptionId);
    }
    public void insertReversalHistory(RedemptionReversalHistory entity) { mapper.insertReversalHistory(com.whale.crm.platform.context.TenantContext.currentTenantId(), entity); }
    public List<RedemptionReversalHistory> findReversalHistory(long tenantId, long id) { return mapper.selectReversalHistory(tenantId, id); }
}
