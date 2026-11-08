package com.whale.crm.fulfillment.application;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.fulfillment.api.dto.CreateReversalRequest;
import com.whale.crm.fulfillment.api.dto.PostRedemptionRequest;
import com.whale.crm.fulfillment.api.dto.RedemptionDetail;
import com.whale.crm.fulfillment.api.dto.RedemptionLineRequest;
import com.whale.crm.fulfillment.api.dto.ReversalDetail;
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
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.trading.application.FulfillmentTradingOperations;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.FulfillmentEntitlementView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RedemptionApplicationService {
    private static final String POST_SCOPE = "redemption.post";
    private static final String REVERSAL_REQUEST_SCOPE = "redemption.reversal.request";

    private final FulfillmentRepository repository;
    private final FulfillmentSupport support;
    private final CustomerScopeSupport scopeSupport;
    private final InstitutionAccessSupport institutionAccess;
    private final FulfillmentTradingOperations tradingOperations;
    private final FileApplicationService fileOperations;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public RedemptionApplicationService(FulfillmentRepository repository, FulfillmentSupport support,
                                        CustomerScopeSupport scopeSupport, InstitutionAccessSupport institutionAccess,
                                        FulfillmentTradingOperations tradingOperations, FileApplicationService fileOperations, OutboxService outboxService,
                                        AuditService auditService, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.support = support;
        this.scopeSupport = scopeSupport;
        this.institutionAccess = institutionAccess;
        this.tradingOperations = tradingOperations;
        this.fileOperations = fileOperations;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<Redemption> search(long tenantId, CurrentUser user, Long customerId, Long institutionId,
                                           Long projectId, String status, int page, int size) {
        FulfillmentScope scope = support.scope(tenantId, user);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchRedemptions(tenantId, scope, customerId, institutionId,
                        projectId, status, offset, size),
                repository.countRedemptions(tenantId, scope, customerId, institutionId, projectId, status), page, size);
    }

    @Transactional(readOnly = true)
    public RedemptionDetail detail(long tenantId, CurrentUser user, long redemptionId) {
        return support.redemptionDetail(tenantId, user, redemptionId);
    }

    public RedemptionDetail post(long tenantId, CurrentUser user, long appointmentId,
                                 PostRedemptionRequest request, String idempotencyKey) {
        Appointment visible = support.requireAppointmentVisible(tenantId, user, appointmentId);
        institutionAccess.requireOperator(tenantId, user, visible.institutionId());
        String key = support.requireKey(idempotencyKey);
        String payload = support.canonical(PostCanonical.from(appointmentId, request));
        IdempotencyRecord replay = support.lookup(tenantId, POST_SCOPE, key, payload);
        if (replay != null) {
            return support.redemptionDetail(tenantId, user, support.resourceId(replay));
        }
        try {
            return transactionTemplate.execute(status -> doPost(tenantId, user, appointmentId, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayPost(tenantId, user, appointmentId, key, payload));
        }
    }

    public ReversalDetail requestReversal(long tenantId, CurrentUser user, long redemptionId,
                                          CreateReversalRequest request, String idempotencyKey) {
        Redemption visible = support.requireRedemptionVisible(tenantId, user, redemptionId);
        if (user.isInstitutionUser()) {
            institutionAccess.requireStaff(tenantId, user, visible.institutionId());
        }
        String key = support.requireKey(idempotencyKey);
        String payload = support.canonical(new ReversalRequestCanonical(redemptionId, request.redemptionVersion(), request.reason()));
        IdempotencyRecord replay = support.lookup(tenantId, REVERSAL_REQUEST_SCOPE, key, payload);
        if (replay != null) {
            return support.reversalDetail(tenantId, user, support.resourceId(replay));
        }
        try {
            return transactionTemplate.execute(status -> doRequestReversal(tenantId, user, redemptionId,
                    request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayReversalRequest(tenantId, user, redemptionId, key, payload));
        }
    }

    private RedemptionDetail doPost(long tenantId, CurrentUser user, long appointmentId,
                                    PostRedemptionRequest request, String key, String payload) {
        Appointment appointment = repository.findAppointmentForUpdate(tenantId, appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预约不存在"));
        institutionAccess.requireOperator(tenantId, user, appointment.institutionId());
        IdempotencyRecord replay = support.lookup(tenantId, POST_SCOPE, key, payload);
        if (replay != null) {
            return support.redemptionDetail(tenantId, user, support.resourceId(replay));
        }
        if (!"SERVICE_DONE".equals(appointment.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有服务已完成的预约才能由机构正式核销");
        }
        if (!appointment.version().equals(request.appointmentVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "预约版本已变化，请刷新后重试");
        }
        validateServiceTime(tenantId, appointmentId, request.serviceAt());
        List<AppointmentItem> appointmentItems = repository.findAppointmentItems(tenantId, appointmentId);
        Map<Long, AppointmentItem> itemsByEntitlement = appointmentItems.stream()
                .collect(Collectors.toMap(AppointmentItem::entitlementId, Function.identity()));
        Map<Long, Integer> postedByItem = postedCounts(tenantId, appointmentId);
        List<RedemptionLineRequest> lines = request.lines().stream()
                .sorted(Comparator.comparingLong(RedemptionLineRequest::entitlementId)).toList();
        Set<Long> unique = new HashSet<>();
        Map<Long, FulfillmentEntitlementView> locked = new HashMap<>();
        for (RedemptionLineRequest line : lines) {
            if (!unique.add(line.entitlementId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同一权益批次在一个核销批次中只能出现一次");
            }
            AppointmentItem item = itemsByEntitlement.get(line.entitlementId());
            if (item == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "核销权益批次不属于该预约项目");
            }
            int remaining = item.plannedCount() - postedByItem.getOrDefault(item.id(), 0);
            if (line.count() > remaining) {
                throw new BusinessException(ErrorCode.CONFLICT, "核销次数超过预约项目剩余计划次数");
            }
            locked.put(line.entitlementId(), tradingOperations.lockRedeemable(tenantId, line.entitlementId(),
                    appointment.customerId(), appointment.institutionId(), item.projectId(), line.count(), request.serviceAt()));
        }

        if (request.voucherFileId() != null) {
            fileOperations.requireCleanUsable(tenantId, user, request.voucherFileId(),
                    FileClassification.REDEMPTION, appointment.institutionId());
        }
        Instant now = Instant.now();
        Redemption input = new Redemption(null, appointmentId, appointment.customerId(), appointment.institutionId(),
                appointment.ownerStaffId(), appointment.teamId(), request.serviceAt(), request.evidenceRef(),
                request.voucherFileId(), "POSTED", "PENDING_VERIFICATION", key, user.userId(), now, null, null);
        repository.insertRedemption(input);
        Redemption created = repository.findRedemptionByKey(tenantId, key).orElseThrow();
        for (RedemptionLineRequest line : lines) {
            AppointmentItem item = itemsByEntitlement.get(line.entitlementId());
            repository.insertRedemptionLine(new RedemptionLine(null, created.id(), item.id(), item.entitlementId(),
                    item.projectId(), line.count(), null));
        }
        List<RedemptionLine> persistedLines = repository.findRedemptionLines(tenantId, created.id());
        for (RedemptionLine line : persistedLines) {
            tradingOperations.appendRedemption(tenantId, user.userId(), locked.get(line.entitlementId()), line.count(),
                    line.id().toString(), "redemption.post:" + line.id());
        }
        repository.insertRedemptionHistory(new RedemptionStatusHistory(null, created.id(),
                "PENDING_INSTITUTION", "POSTED", user.userId(), request.evidenceRef(), null));

        Map<Long, Integer> afterPosted = new HashMap<>(postedByItem);
        for (RedemptionLineRequest line : lines) {
            long itemId = itemsByEntitlement.get(line.entitlementId()).id();
            afterPosted.merge(itemId, line.count(), Integer::sum);
        }
        boolean completed = appointmentItems.stream().allMatch(item ->
                item.plannedCount().equals(afterPosted.getOrDefault(item.id(), 0)));
        String appointmentStatus = completed ? "REDEEMED" : "SERVICE_DONE";
        if (repository.updateAppointmentStatus(tenantId, appointmentId, request.appointmentVersion(),
                "SERVICE_DONE", appointmentStatus) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "预约已被其他履约操作修改，请重试");
        }
        repository.insertAppointmentHistory(new AppointmentHistory(null, appointmentId, "SERVICE_DONE",
                appointmentStatus, user.userId(), "REDEMPTION_POSTED:" + created.id(), null));
        List<Map<String, Object>> eventLines = persistedLines.stream()
                .map(line -> Map.<String, Object>of("lineId", line.id(), "entitlementId", line.entitlementId(),
                        "projectId", line.projectId(), "count", line.count()))
                .toList();
        outboxService.append(tenantId, "redemption.posted.v1:" + created.id(), "redemption.posted.v1",
                "redemption", Long.toString(created.id()), Map.of("redemptionId", created.id(),
                        "appointmentId", appointmentId, "institutionId", appointment.institutionId(),
                        "customerId", appointment.customerId(), "lines", eventLines,
                        "settlementStatus", "PENDING_VERIFICATION"));
        RedemptionDetail after = support.redemptionDetail(tenantId, user, created.id());
        auditService.record(tenantId, user.userId(), "REDEMPTION_POST", "REDEMPTION",
                Long.toString(created.id()), appointment, after, request.evidenceRef());
        support.recordSuccess(tenantId, POST_SCOPE, key, "REDEMPTION", created.id(), payload);
        return after;
    }

    private ReversalDetail doRequestReversal(long tenantId, CurrentUser user, long redemptionId,
                                             CreateReversalRequest request, String key, String payload) {
        Redemption redemption = repository.findRedemptionForUpdate(tenantId, redemptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "核销记录不存在"));
        if (user.isInstitutionUser()) {
            institutionAccess.requireStaff(tenantId, user, redemption.institutionId());
        } else {
            scopeSupport.requireVisible(tenantId, redemption.customerId(), user);
        }
        IdempotencyRecord replay = support.lookup(tenantId, REVERSAL_REQUEST_SCOPE, key, payload);
        if (replay != null) {
            return support.reversalDetail(tenantId, user, support.resourceId(replay));
        }
        if (!redemption.version().equals(request.redemptionVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "核销事实版本已变化，请刷新后重试");
        }
        if (repository.findActiveReversalByRedemption(tenantId, redemptionId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该核销已存在待审核或已生效的冲正申请");
        }
        RedemptionReversal input = new RedemptionReversal(null, redemptionId, redemption.customerId(),
                redemption.institutionId(), redemption.ownerStaffId(), redemption.teamId(), "REQUESTED",
                request.reason(), user.userId(), Instant.now(), null, null, null, key, null,
                redemptionId, null, null);
        repository.insertReversal(input);
        RedemptionReversal created = repository.findReversalByRequestKey(tenantId, key).orElseThrow();
        repository.insertReversalHistory(new RedemptionReversalHistory(null, created.id(), "NONE",
                "REQUESTED", user.userId(), request.reason(), null));
        outboxService.append(tenantId, "redemption.reversal.requested.v1:" + created.id(),
                "redemption.reversal.requested.v1", "redemption_reversal", Long.toString(created.id()),
                Map.of("reversalId", created.id(), "redemptionId", redemptionId, "requestedBy", user.userId()));
        ReversalDetail after = support.reversalDetail(tenantId, user, created.id());
        auditService.record(tenantId, user.userId(), "REDEMPTION_REVERSAL_REQUEST", "REDEMPTION_REVERSAL",
                Long.toString(created.id()), redemption, after, request.reason());
        support.recordSuccess(tenantId, REVERSAL_REQUEST_SCOPE, key, "REDEMPTION_REVERSAL", created.id(), payload);
        return after;
    }

    private void validateServiceTime(long tenantId, long appointmentId, Instant serviceAt) {
        if (serviceAt.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "实际服务时间不能晚于当前时间");
        }
        Instant checkedInAt = repository.findAppointmentHistory(tenantId, appointmentId).stream()
                .filter(history -> "CHECKED_IN".equals(history.toStatus()))
                .map(AppointmentHistory::createdAt)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "缺少真实到院记录，不能正式核销"));
        if (serviceAt.isBefore(checkedInAt)) {
            throw new BusinessException(ErrorCode.CONFLICT, "实际服务时间不能早于实际到院时间");
        }
    }

    private Map<Long, Integer> postedCounts(long tenantId, long appointmentId) {
        return repository.findPostedLinesByAppointment(tenantId, appointmentId).stream()
                .collect(Collectors.groupingBy(RedemptionLine::appointmentItemId,
                        Collectors.summingInt(RedemptionLine::count)));
    }

    private RedemptionDetail replayPost(long tenantId, CurrentUser user, long appointmentId,
                                        String key, String payload) {
        Appointment appointment = repository.findAppointmentForUpdate(tenantId, appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预约不存在"));
        institutionAccess.requireOperator(tenantId, user, appointment.institutionId());
        IdempotencyRecord record = support.lookup(tenantId, POST_SCOPE, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "核销冲突且没有已提交幂等结果");
        }
        RedemptionDetail detail = support.redemptionDetail(tenantId, user, support.resourceId(record));
        if (!detail.redemption().appointmentId().equals(appointmentId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果不属于该预约");
        }
        return detail;
    }

    private ReversalDetail replayReversalRequest(long tenantId, CurrentUser user, long redemptionId,
                                                 String key, String payload) {
        Redemption redemption = repository.findRedemptionForUpdate(tenantId, redemptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "核销记录不存在"));
        if (user.isInstitutionUser()) {
            institutionAccess.requireStaff(tenantId, user, redemption.institutionId());
        } else {
            scopeSupport.requireVisible(tenantId, redemption.customerId(), user);
        }
        IdempotencyRecord record = support.lookup(tenantId, REVERSAL_REQUEST_SCOPE, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "冲正申请冲突且没有已提交幂等结果");
        }
        ReversalDetail detail = support.reversalDetail(tenantId, user, support.resourceId(record));
        if (!detail.reversal().redemptionId().equals(redemptionId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "冲正幂等结果不属于该核销");
        }
        return detail;
    }

    private record LineCanonical(long entitlementId, int count) {
        static LineCanonical from(RedemptionLineRequest request) {
            return new LineCanonical(request.entitlementId(), request.count());
        }
    }

    private record PostCanonical(long appointmentId, long appointmentVersion, String serviceAt,
                                 String evidenceRef, Long voucherFileId, List<LineCanonical> lines) {
        static PostCanonical from(long appointmentId, PostRedemptionRequest request) {
            return new PostCanonical(appointmentId, request.appointmentVersion(), request.serviceAt().toString(),
                    request.evidenceRef(), request.voucherFileId(), request.lines().stream().map(LineCanonical::from)
                    .sorted(Comparator.comparingLong(LineCanonical::entitlementId)).toList());
        }
    }

    private record ReversalRequestCanonical(long redemptionId, long redemptionVersion, String reason) {
    }
}
