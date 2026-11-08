package com.whale.crm.fulfillment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.fulfillment.api.dto.AppointmentDetail;
import com.whale.crm.fulfillment.api.dto.AppointmentItemView;
import com.whale.crm.fulfillment.api.dto.RedemptionDetail;
import com.whale.crm.fulfillment.api.dto.ReversalDetail;
import com.whale.crm.fulfillment.domain.Appointment;
import com.whale.crm.fulfillment.domain.AppointmentItem;
import com.whale.crm.fulfillment.domain.FulfillmentRepository;
import com.whale.crm.fulfillment.domain.FulfillmentScope;
import com.whale.crm.fulfillment.domain.Redemption;
import com.whale.crm.fulfillment.domain.RedemptionLine;
import com.whale.crm.fulfillment.domain.RedemptionReversal;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.application.FulfillmentTradingOperations;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class FulfillmentSupport {
    private final FulfillmentRepository repository;
    private final CustomerScopeSupport scopeSupport;
    private final InstitutionAccessSupport institutionAccess;
    private final IdempotencyService idempotencyService;
    private final FulfillmentTradingOperations tradingOperations;
    private final ObjectMapper objectMapper;

    public FulfillmentSupport(FulfillmentRepository repository, CustomerScopeSupport scopeSupport,
                              InstitutionAccessSupport institutionAccess, IdempotencyService idempotencyService,
                              FulfillmentTradingOperations tradingOperations, ObjectMapper objectMapper) {
        this.repository = repository;
        this.scopeSupport = scopeSupport;
        this.institutionAccess = institutionAccess;
        this.idempotencyService = idempotencyService;
        this.tradingOperations = tradingOperations;
        this.objectMapper = objectMapper;
    }

    public String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供1至128字符的幂等键");
        }
        return idempotencyKey.strip();
    }

    public String canonical(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("履约请求规范化失败", ex);
        }
    }

    public IdempotencyRecord lookup(long tenantId, String scope, String key, String payload) {
        return idempotencyService.lookup(tenantId, scope, key, payload).orElse(null);
    }

    public void recordSuccess(long tenantId, String scope, String key, String resourceType,
                              long resourceId, String payload) {
        idempotencyService.recordSuccess(tenantId, scope, key, resourceType, Long.toString(resourceId), payload);
    }

    public long resourceId(IdempotencyRecord record) {
        return Long.parseLong(record.getResourceId());
    }

    public Appointment requireAppointmentVisible(long tenantId, CurrentUser user, long appointmentId) {
        Appointment appointment = repository.findAppointment(tenantId, appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预约不存在或不可见"));
        requireVisible(tenantId, user, appointment.customerId(), appointment.institutionId());
        return appointment;
    }

    public Redemption requireRedemptionVisible(long tenantId, CurrentUser user, long redemptionId) {
        Redemption redemption = repository.findRedemption(tenantId, redemptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "核销记录不存在或不可见"));
        requireVisible(tenantId, user, redemption.customerId(), redemption.institutionId());
        return redemption;
    }

    public RedemptionReversal requireReversalVisible(long tenantId, CurrentUser user, long reversalId) {
        RedemptionReversal reversal = repository.findReversal(tenantId, reversalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "冲正申请不存在或不可见"));
        requireVisible(tenantId, user, reversal.customerId(), reversal.institutionId());
        return reversal;
    }

    public AppointmentDetail appointmentDetail(long tenantId, CurrentUser user, long appointmentId) {
        Appointment appointment = requireAppointmentVisible(tenantId, user, appointmentId);
        List<AppointmentItem> items = repository.findAppointmentItems(tenantId, appointmentId);
        Map<Long, Integer> posted = repository.findPostedLinesByAppointment(tenantId, appointmentId).stream()
                .collect(Collectors.groupingBy(RedemptionLine::appointmentItemId,
                        Collectors.summingInt(RedemptionLine::count)));
        List<AppointmentItemView> itemViews = items.stream()
                .map(item -> new AppointmentItemView(item.id(), item.entitlementId(), item.projectId(),
                        item.plannedCount(), posted.getOrDefault(item.id(), 0)))
                .toList();
        List<RedemptionDetail> redemptions = repository.findRedemptionsByAppointment(tenantId, appointmentId).stream()
                .map(redemption -> redemptionDetail(tenantId, user, redemption.id()))
                .toList();
        return new AppointmentDetail(appointment, itemViews,
                repository.findAppointmentHistory(tenantId, appointmentId), redemptions);
    }

    public RedemptionDetail redemptionDetail(long tenantId, CurrentUser user, long redemptionId) {
        Redemption redemption = requireRedemptionVisible(tenantId, user, redemptionId);
        return new RedemptionDetail(redemption, repository.findRedemptionLines(tenantId, redemptionId),
                repository.findRedemptionHistory(tenantId, redemptionId));
    }

    public ReversalDetail reversalDetail(long tenantId, CurrentUser user, long reversalId) {
        RedemptionReversal reversal = requireReversalVisible(tenantId, user, reversalId);
        return new ReversalDetail(reversal, repository.findReversalHistory(tenantId, reversalId),
                tradingOperations.corrections(tenantId, reversalId));
    }

    public FulfillmentScope scope(long tenantId, CurrentUser user) {
        if (user.isInstitutionUser()) {
            return new FulfillmentScope("INSTITUTION", List.of(), user.userId(),
                    institutionAccess.visibleInstitutionIds(tenantId, user));
        }
        DataScope scope = scopeSupport.scope(user);
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        return new FulfillmentScope(query.scopeType(), query.teamIds(), query.selfId(), null);
    }

    private void requireVisible(long tenantId, CurrentUser user, long customerId, long institutionId) {
        if (user.isInstitutionUser()) {
            if (!institutionAccess.canAccess(tenantId, user, institutionId)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "记录不存在或不在本机构范围内");
            }
            return;
        }
        scopeSupport.requireVisible(tenantId, customerId, user);
    }
}
