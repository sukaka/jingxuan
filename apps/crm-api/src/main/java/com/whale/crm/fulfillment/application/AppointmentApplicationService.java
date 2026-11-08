package com.whale.crm.fulfillment.application;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.fulfillment.api.dto.AppointmentDetail;
import com.whale.crm.fulfillment.api.dto.AppointmentItemRequest;
import com.whale.crm.fulfillment.api.dto.AppointmentOptionsResponse;
import com.whale.crm.fulfillment.api.dto.AppointmentTransitionRequest;
import com.whale.crm.fulfillment.api.dto.CreateAppointmentRequest;
import com.whale.crm.fulfillment.domain.Appointment;
import com.whale.crm.fulfillment.domain.AppointmentHistory;
import com.whale.crm.fulfillment.domain.AppointmentItem;
import com.whale.crm.fulfillment.domain.FulfillmentRepository;
import com.whale.crm.fulfillment.domain.FulfillmentScope;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.trading.application.FulfillmentTradingOperations;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.FulfillmentEntitlementView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AppointmentApplicationService {
    private static final String CREATE_SCOPE = "appointment.create";
    private static final String TRANSITION_SCOPE = "appointment.transition";

    private final FulfillmentRepository repository;
    private final FulfillmentSupport support;
    private final CustomerScopeSupport scopeSupport;
    private final InstitutionAccessSupport institutionAccess;
    private final CatalogApplicationService catalogService;
    private final FulfillmentTradingOperations tradingOperations;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public AppointmentApplicationService(FulfillmentRepository repository, FulfillmentSupport support,
                                         CustomerScopeSupport scopeSupport, InstitutionAccessSupport institutionAccess,
                                         CatalogApplicationService catalogService,
                                         FulfillmentTradingOperations tradingOperations,
                                         OutboxService outboxService, AuditService auditService,
                                         PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.support = support;
        this.scopeSupport = scopeSupport;
        this.institutionAccess = institutionAccess;
        this.catalogService = catalogService;
        this.tradingOperations = tradingOperations;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<Appointment> search(long tenantId, CurrentUser user, Long customerId, Long institutionId,
                                            String status, int page, int size) {
        FulfillmentScope scope = support.scope(tenantId, user);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchAppointments(tenantId, scope, customerId, institutionId,
                        status, offset, size),
                repository.countAppointments(tenantId, scope, customerId, institutionId, status), page, size);
    }

    @Transactional(readOnly = true)
    public AppointmentDetail detail(long tenantId, CurrentUser user, long appointmentId) {
        return support.appointmentDetail(tenantId, user, appointmentId);
    }

    @Transactional(readOnly = true)
    public AppointmentOptionsResponse options(long tenantId, CurrentUser user, Long customerId, Long appointmentId) {
        if (user.isInstitutionUser()) {
            return institutionOptions(tenantId, user, appointmentId);
        }
        if (customerId == null) {
            if (appointmentId != null) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "非机构账号不能使用机构预约选项模式");
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "管家查询预约选项必须提供customerId");
        }
        return toOptionsResponse(tenantId, user, customerId,
                tradingOperations.appointmentOptions(tenantId, user, customerId), null);
    }

    private AppointmentOptionsResponse institutionOptions(long tenantId, CurrentUser user, Long appointmentId) {
        if (appointmentId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "机构查询核销选项必须提供appointmentId");
        }
        Appointment appointment = repository.findAppointment(tenantId, appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预约不存在或不可见"));
        if (!institutionAccess.canAccess(tenantId, user, appointment.institutionId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "预约不存在或不在本机构范围内");
        }
        institutionAccess.requireOperator(tenantId, user, appointment.institutionId());
        List<Long> entitlementIds = repository.findAppointmentItems(tenantId, appointmentId).stream()
                .map(AppointmentItem::entitlementId).toList();
        return toOptionsResponse(tenantId, user, appointment.customerId(),
                tradingOperations.appointmentOptionsForInstitution(tenantId, appointment.customerId(),
                        appointment.institutionId(), entitlementIds), appointment.institutionId());
    }

    private AppointmentOptionsResponse toOptionsResponse(long tenantId, CurrentUser user, long customerId,
                                                         List<FulfillmentEntitlementView> entitlements,
                                                         Long onlyInstitutionId) {
        Set<Long> institutionIds = new LinkedHashSet<>();
        entitlements.forEach(view -> institutionIds.addAll(view.institutionIds()));
        List<AppointmentOptionsResponse.InstitutionOption> institutions = catalogService
                .institutions(tenantId, user, null, "ACTIVE", 1, 100).items().stream()
                .filter(institution -> institutionIds.contains(institution.id()))
                .filter(institution -> onlyInstitutionId == null || onlyInstitutionId.equals(institution.id()))
                .map(institution -> new AppointmentOptionsResponse.InstitutionOption(
                        institution.id(), institution.fullName(), institution.shortName()))
                .toList();
        List<AppointmentOptionsResponse.EntitlementOption> options = entitlements.stream()
                .map(view -> new AppointmentOptionsResponse.EntitlementOption(view.entitlement().id(), view.orderId(),
                        view.entitlement().projectId(), view.entitlement().projectName(),
                        view.entitlement().availableCount(), view.entitlement().validFrom(), view.entitlement().validTo(),
                        view.institutionIds(), view.allocationStatus()))
                .toList();
        return new AppointmentOptionsResponse(customerId, institutions, options);
    }

    public AppointmentDetail create(long tenantId, CurrentUser user, CreateAppointmentRequest request,
                                    String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.canonical(CreateCanonical.from(request));
        IdempotencyRecord replay = support.lookup(tenantId, CREATE_SCOPE, key, payload);
        if (replay != null) {
            return support.appointmentDetail(tenantId, user, support.resourceId(replay));
        }
        try {
            return transactionTemplate.execute(status -> doCreate(tenantId, user, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayCreate(tenantId, user, key, payload));
        }
    }

    public AppointmentDetail transition(long tenantId, CurrentUser user, long appointmentId, String action,
                                        AppointmentTransitionRequest request, String idempotencyKey) {
        Appointment visible = support.requireAppointmentVisible(tenantId, user, appointmentId);
        if ("cancel".equals(action)) {
            if (user.isInstitutionUser()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "机构账号不能代替管家取消预约");
            }
        } else {
            institutionAccess.requireStaff(tenantId, user, visible.institutionId());
        }
        String key = support.requireKey(idempotencyKey);
        String payload = support.canonical(new TransitionCanonical(appointmentId, action, request.version(), request.reason()));
        IdempotencyRecord replay = support.lookup(tenantId, TRANSITION_SCOPE, key, payload);
        if (replay != null) {
            return support.appointmentDetail(tenantId, user, appointmentId);
        }
        try {
            return transactionTemplate.execute(status -> doTransition(tenantId, user, appointmentId, action,
                    request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayTransition(tenantId, user, appointmentId, key, payload));
        }
    }

    private AppointmentDetail doCreate(long tenantId, CurrentUser user, CreateAppointmentRequest request,
                                       String key, String payload) {
        Customer customer = scopeSupport.requireVisible(tenantId, request.customerId(), user);
        requireActiveInstitution(tenantId, user, request.institutionId());
        if (support.lookup(tenantId, CREATE_SCOPE, key, payload) != null) {
            IdempotencyRecord record = support.lookup(tenantId, CREATE_SCOPE, key, payload);
            return support.appointmentDetail(tenantId, user, support.resourceId(record));
        }
        List<AppointmentItemRequest> items = request.items().stream()
                .sorted(Comparator.comparing(AppointmentItemRequest::entitlementId)).toList();
        Set<Long> unique = new HashSet<>();
        for (AppointmentItemRequest item : items) {
            if (!unique.add(item.entitlementId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同一权益批次在预约中只能出现一次");
            }
        }
        String temporaryNo = "TMP-A-" + UUID.randomUUID();
        Appointment input = new Appointment(null, temporaryNo, customer.getId(), request.institutionId(),
                customer.getOwnerStaffId(), customer.getTeamId(), request.scheduledAt(), request.remark(),
                "PENDING_INSTITUTION", user.userId(), null, null);
        repository.insertAppointment(input);
        Appointment created = repository.findAppointmentByNo(tenantId, temporaryNo).orElseThrow();
        repository.backfillAppointmentNo(tenantId, created.id(), String.format("A%012d", created.id()));
        for (AppointmentItemRequest item : items) {
            FulfillmentEntitlementView view = tradingOperations.requireAppointmentEntitlement(tenantId,
                    item.entitlementId(), customer.getId(), request.institutionId(), item.count(), request.scheduledAt());
            repository.insertAppointmentItem(new AppointmentItem(null, created.id(), item.entitlementId(),
                    view.entitlement().projectId(), item.count(), null, null));
        }
        repository.insertAppointmentHistory(new AppointmentHistory(null, created.id(), "NONE",
                "PENDING_INSTITUTION", user.userId(), "创建预约", null));
        outboxService.append(tenantId, "appointment.created.v1:" + created.id(), "appointment.created.v1",
                "appointment", Long.toString(created.id()), Map.of("appointmentId", created.id(),
                        "customerId", customer.getId(), "institutionId", request.institutionId(),
                        "scheduledAt", request.scheduledAt().toString()));
        AppointmentDetail after = support.appointmentDetail(tenantId, user, created.id());
        auditService.record(tenantId, user.userId(), "APPOINTMENT_CREATE", "APPOINTMENT",
                Long.toString(created.id()), null, after, request.remark());
        support.recordSuccess(tenantId, CREATE_SCOPE, key, "APPOINTMENT", created.id(), payload);
        return after;
    }

    private AppointmentDetail doTransition(long tenantId, CurrentUser user, long appointmentId, String action,
                                           AppointmentTransitionRequest request, String key, String payload) {
        Appointment before = repository.findAppointmentForUpdate(tenantId, appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预约不存在"));
        if ("cancel".equals(action)) {
            if (user.isInstitutionUser()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "机构账号不能代替管家取消预约");
            }
            scopeSupport.requireVisible(tenantId, before.customerId(), user);
        } else {
            institutionAccess.requireStaff(tenantId, user, before.institutionId());
        }
        IdempotencyRecord replay = support.lookup(tenantId, TRANSITION_SCOPE, key, payload);
        if (replay != null) {
            return support.appointmentDetail(tenantId, user, appointmentId);
        }
        Transition transition = Transition.forAction(action);
        if (!transition.fromStatuses().contains(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "预约当前状态不允许该操作");
        }
        if (!before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "预约版本已变化，请刷新后重试");
        }
        if (repository.updateAppointmentStatus(tenantId, appointmentId, request.version(), before.status(),
                transition.toStatus()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "预约已被其他操作修改，请重试");
        }
        repository.insertAppointmentHistory(new AppointmentHistory(null, appointmentId, before.status(),
                transition.toStatus(), user.userId(), request.reason(), null));
        long newVersion = request.version() + 1;
        outboxService.append(tenantId, "appointment.status.changed.v1:" + appointmentId + ":" + newVersion,
                "appointment.status.changed.v1", "appointment", Long.toString(appointmentId),
                Map.of("appointmentId", appointmentId, "fromStatus", before.status(),
                        "toStatus", transition.toStatus(), "actorUserId", user.userId()));
        AppointmentDetail after = support.appointmentDetail(tenantId, user, appointmentId);
        auditService.record(tenantId, user.userId(), "APPOINTMENT_" + action.toUpperCase().replace('-', '_'),
                "APPOINTMENT", Long.toString(appointmentId), before, after, request.reason());
        support.recordSuccess(tenantId, TRANSITION_SCOPE, key, "APPOINTMENT", appointmentId, payload);
        return after;
    }

    private AppointmentDetail replayCreate(long tenantId, CurrentUser user, String key, String payload) {
        IdempotencyRecord record = support.lookup(tenantId, CREATE_SCOPE, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "预约创建冲突且没有已提交幂等结果");
        }
        return support.appointmentDetail(tenantId, user, support.resourceId(record));
    }

    private AppointmentDetail replayTransition(long tenantId, CurrentUser user, long appointmentId,
                                               String key, String payload) {
        IdempotencyRecord record = support.lookup(tenantId, TRANSITION_SCOPE, key, payload);
        if (record == null || support.resourceId(record) != appointmentId) {
            throw new BusinessException(ErrorCode.CONFLICT, "预约操作冲突且没有匹配的幂等结果");
        }
        return support.appointmentDetail(tenantId, user, appointmentId);
    }

    private void requireActiveInstitution(long tenantId, CurrentUser user, long institutionId) {
        boolean active = catalogService.institutions(tenantId, user, null, "ACTIVE", 1, 100).items().stream()
                .anyMatch(institution -> institution.id().equals(institutionId));
        if (!active) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "预约机构不存在或未启用");
        }
    }

    private record ItemCanonical(long entitlementId, int count) {
        static ItemCanonical from(AppointmentItemRequest request) {
            return new ItemCanonical(request.entitlementId(), request.count());
        }
    }

    private record CreateCanonical(long customerId, long institutionId, String scheduledAt,
                                   String remark, List<ItemCanonical> items) {
        static CreateCanonical from(CreateAppointmentRequest request) {
            return new CreateCanonical(request.customerId(), request.institutionId(), request.scheduledAt().toString(),
                    request.remark(), request.items().stream().map(ItemCanonical::from)
                    .sorted(Comparator.comparingLong(ItemCanonical::entitlementId)).toList());
        }
    }

    private record TransitionCanonical(long appointmentId, String action, long version, String reason) {
    }

    private record Transition(Set<String> fromStatuses, String toStatus) {
        static Transition forAction(String action) {
            return switch (action) {
                case "confirm" -> new Transition(Set.of("PENDING_INSTITUTION"), "CONFIRMED");
                case "reject" -> new Transition(Set.of("PENDING_INSTITUTION"), "REJECTED");
                case "check-in" -> new Transition(Set.of("CONFIRMED"), "CHECKED_IN");
                case "service-done" -> new Transition(Set.of("CHECKED_IN"), "SERVICE_DONE");
                case "no-show" -> new Transition(Set.of("CONFIRMED"), "NO_SHOW");
                case "cancel" -> new Transition(Set.of("PENDING_INSTITUTION", "CONFIRMED"), "CANCELED");
                default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未知预约操作");
            };
        }
    }
}
