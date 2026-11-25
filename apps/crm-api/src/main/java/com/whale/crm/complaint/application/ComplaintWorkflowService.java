package com.whale.crm.complaint.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.complaint.api.dto.ComplaintCaseDetailResponse;
import com.whale.crm.complaint.api.dto.ComplaintCaseHistoryResponse;
import com.whale.crm.complaint.api.dto.ComplaintCaseResponse;
import com.whale.crm.complaint.api.dto.ComplaintEmpathyScriptResponse;
import com.whale.crm.complaint.api.dto.ComplaintHitDetailResponse;
import com.whale.crm.complaint.api.dto.ComplaintHitHistoryResponse;
import com.whale.crm.complaint.api.dto.ComplaintHitResponse;
import com.whale.crm.complaint.domain.ComplaintCase;
import com.whale.crm.complaint.domain.ComplaintCaseHistory;
import com.whale.crm.complaint.domain.ComplaintCaseQuery;
import com.whale.crm.complaint.domain.ComplaintEmpathyScriptRepository;
import com.whale.crm.complaint.domain.ComplaintHit;
import com.whale.crm.complaint.domain.ComplaintHitHistory;
import com.whale.crm.complaint.domain.ComplaintHitQuery;
import com.whale.crm.complaint.domain.ComplaintPolicy;
import com.whale.crm.complaint.domain.ComplaintRepository;
import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.application.CustomerTimelineCursor;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ComplaintWorkflowService {
    private final ComplaintRepository repository;
    private final ComplaintEmpathyScriptRepository empathyScriptRepository;
    private final ComplaintAccessService accessService;
    private final ComplaintMasker masker;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ComplaintWorkflowService(ComplaintRepository repository,
                                    ComplaintEmpathyScriptRepository empathyScriptRepository,
                                    ComplaintAccessService accessService, ComplaintMasker masker,
                                    IdempotencyService idempotencyService, AuditService auditService,
                                    ObjectMapper objectMapper) {
        this.repository = repository;
        this.empathyScriptRepository = empathyScriptRepository;
        this.accessService = accessService;
        this.masker = masker;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<ComplaintHitResponse> searchHits(CurrentUser user, String status, String routingStatus,
                                                          String keyword, int page, int size) {
        accessService.require(user, "complaint:read");
        validatePage(page, size);
        DataScope scope = accessService.scope(user);
        if (accessService.isEmpty(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery scoped = accessService.query(scope, user);
        ComplaintHitQuery query = new ComplaintHitQuery(user.tenantId(), scoped.scopeType(), scoped.teamIds(),
                scoped.selfId(), status, routingStatus, keyword, (page - 1) * size, size);
        return new PageResponse<>(repository.searchHits(query).stream().map(ComplaintHitResponse::from).toList(),
                repository.countHits(query), page, size);
    }

    @Transactional(readOnly = true)
    public ComplaintHitDetailResponse getHit(CurrentUser user, long id) {
        accessService.require(user, "complaint:read");
        ComplaintHit hit = repository.findHitById(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("客诉命中不存在或不可见"));
        return hitDetail(accessService.requireHitVisible(user, hit));
    }

    @Transactional(readOnly = true)
    public PageResponse<ComplaintCaseResponse> searchCases(CurrentUser user, String status, String keyword,
                                                            int page, int size) {
        accessService.require(user, "complaint:read");
        validatePage(page, size);
        DataScope scope = accessService.scope(user);
        if (accessService.isEmpty(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery scoped = accessService.query(scope, user);
        ComplaintCaseQuery query = new ComplaintCaseQuery(user.tenantId(), scoped.scopeType(), scoped.teamIds(),
                scoped.selfId(), status, keyword, (page - 1) * size, size);
        return new PageResponse<>(repository.searchCases(query).stream().map(ComplaintCaseResponse::from).toList(),
                repository.countCases(query), page, size);
    }

    @Transactional(readOnly = true)
    public List<CustomerTimelineItemResponse> timeline(CurrentUser user, long customerId,
                                                        CustomerTimelineCursor cursor) {
        accessService.requireCustomerVisible(user, customerId);
        List<CustomerTimelineItemResponse> events = new java.util.ArrayList<>();
        int page = 1;
        long total;
        do {
            PageResponse<ComplaintCaseResponse> result = searchCases(user, null, null, page, 100);
            total = result.total();
            for (ComplaintCaseResponse item : result.items()) {
                if (item.customerId() != customerId) {
                    continue;
                }
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("status", item.status());
                details.put("complaintCaseNo", item.complaintCaseNo());
                CustomerTimelineItemResponse event = CustomerTimelineItemResponse.of("COMPLAINT", item.id(),
                        "COMPLAINT", item.createdAt(), "正式客诉 " + item.complaintCaseNo(), item.resolutionMasked(),
                        details, item.createdBy());
                if (cursor == null || cursor.allows(event)) {
                    events.add(event);
                }
            }
            page++;
        } while ((long) (page - 1) * 100 < total);
        return events;
    }

    @Transactional(readOnly = true)
    public ComplaintCaseDetailResponse getCase(CurrentUser user, long id) {
        accessService.require(user, "complaint:read");
        ComplaintCase complaintCase = repository.findCaseById(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("客诉单不存在或不可见"));
        return caseDetail(accessService.requireCaseVisible(user, complaintCase));
    }

    @Transactional
    public ComplaintCaseDetailResponse confirmHit(CurrentUser user, long id, long version, String reasonInput,
                                                   String idempotencyKey) {
        accessService.require(user, "complaint:write");
        String reason = masker.requiredMasked(reasonInput, "确认理由", 1000);
        String scope = "complaint.hit.confirm";
        String payload = payload(id, version, reason);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), scope, idempotencyKey, payload);
        if (replay.isPresent()) {
            long caseId = resourceId(replay.orElseThrow());
            ComplaintCase stored = repository.findCaseById(user.tenantId(), caseId)
                    .orElseThrow(() -> accessService.notFound("客诉单不存在或不可见"));
            return caseDetail(accessService.requireCaseVisible(user, stored));
        }
        ComplaintHit before = repository.findHitByIdForUpdate(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("客诉命中不存在或不可见"));
        accessService.requireHitVisible(user, before);
        requireVersion(before.version(), version, "客诉命中已变化，请刷新后重试");
        if (!"OPEN".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅待核查命中可确认为正式客诉");
        }
        String caseNo = "CP" + String.format("%012d", id);
        repository.insertCase(new ComplaintCase(null, user.tenantId(), caseNo, id, before.customerId(),
                before.ownerStaffIdSnapshot(), before.teamIdSnapshot(), before.recipientUserIdSnapshot(),
                before.recipientSourceSnapshot(), "OPEN", null, null, null, null, null, user.userId(),
                null, null, 0L));
        ComplaintCase complaintCase = repository.findCaseByHitId(user.tenantId(), id).orElseThrow();
        Instant now = Instant.now();
        if (repository.updateHitReview(user.tenantId(), id, version, "CONVERTED", complaintCase.id(), reason,
                user.userId(), now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "客诉命中已变化，请刷新后重试");
        }
        repository.insertHitHistory(new ComplaintHitHistory(null, user.tenantId(), id, "CONFIRM", "OPEN",
                "CONVERTED", user.userId(), reason, null));
        repository.insertCaseHistory(new ComplaintCaseHistory(null, user.tenantId(), complaintCase.id(), "CONFIRM",
                null, "OPEN", user.userId(), reason, null));
        ComplaintHit afterHit = repository.findHitById(user.tenantId(), id).orElseThrow();
        auditService.record(user.tenantId(), user.userId(), "COMPLAINT_HIT_CONFIRM", "COMPLAINT_HIT",
                Long.toString(id), hitReviewSnapshot(before), hitReviewSnapshot(afterHit), reason);
        auditService.record(user.tenantId(), user.userId(), "COMPLAINT_CASE_CONFIRM", "COMPLAINT_CASE",
                complaintCase.id().toString(), null, caseSnapshot(complaintCase), reason);
        idempotencyService.recordSuccess(user.tenantId(), scope, idempotencyKey, "COMPLAINT_CASE",
                complaintCase.id().toString(), payload);
        return caseDetail(complaintCase);
    }

    @Transactional
    public ComplaintHitDetailResponse dismissHit(CurrentUser user, long id, long version, String reasonInput,
                                                  String idempotencyKey) {
        accessService.require(user, "complaint:write");
        String reason = masker.requiredMasked(reasonInput, "驳回理由", 1000);
        String scope = "complaint.hit.dismiss";
        String payload = payload(id, version, reason);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), scope, idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return hitDetail(requireHit(user, id));
        }
        ComplaintHit before = repository.findHitByIdForUpdate(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("客诉命中不存在或不可见"));
        accessService.requireHitVisible(user, before);
        requireVersion(before.version(), version, "客诉命中已变化，请刷新后重试");
        if (!"OPEN".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅待核查命中可驳回");
        }
        if (repository.updateHitReview(user.tenantId(), id, version, "DISMISSED", null, reason,
                user.userId(), Instant.now()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "客诉命中已变化，请刷新后重试");
        }
        repository.insertHitHistory(new ComplaintHitHistory(null, user.tenantId(), id, "DISMISS", "OPEN",
                "DISMISSED", user.userId(), reason, null));
        ComplaintHit after = repository.findHitById(user.tenantId(), id).orElseThrow();
        auditService.record(user.tenantId(), user.userId(), "COMPLAINT_HIT_DISMISS", "COMPLAINT_HIT",
                Long.toString(id), hitReviewSnapshot(before), hitReviewSnapshot(after), reason);
        idempotencyService.recordSuccess(user.tenantId(), scope, idempotencyKey, "COMPLAINT_HIT",
                Long.toString(id), payload);
        return hitDetail(after);
    }

    @Transactional
    public ComplaintCaseDetailResponse assign(CurrentUser user, long id, long version, String reason,
                                               String idempotencyKey) {
        return transition(user, id, version, reason, idempotencyKey, "ASSIGN", Set.of("OPEN"), "ASSIGNED");
    }

    @Transactional
    public ComplaintCaseDetailResponse resolve(CurrentUser user, long id, long version, String resolution,
                                                String idempotencyKey) {
        return transition(user, id, version, resolution, idempotencyKey, "RESOLVE", Set.of("ASSIGNED"), "RESOLVED");
    }

    @Transactional
    public ComplaintCaseDetailResponse close(CurrentUser user, long id, long version, String reason,
                                              String idempotencyKey) {
        return transition(user, id, version, reason, idempotencyKey, "CLOSE", Set.of("RESOLVED"), "CLOSED");
    }

    @Transactional
    public ComplaintCaseDetailResponse dismiss(CurrentUser user, long id, long version, String reason,
                                                String idempotencyKey) {
        return transition(user, id, version, reason, idempotencyKey, "DISMISS",
                Set.of("OPEN", "ASSIGNED", "RESOLVED"), "DISMISSED");
    }

    private ComplaintCaseDetailResponse transition(CurrentUser user, long id, long version, String reasonInput,
                                                    String idempotencyKey, String action, Set<String> expectedStatuses,
                                                    String targetStatus) {
        accessService.require(user, "complaint:write");
        int maxLength = "RESOLVE".equals(action) ? 2000 : 1000;
        String reason = masker.requiredMasked(reasonInput, "RESOLVE".equals(action) ? "处理结论" : "处理理由", maxLength);
        String scope = "complaint.case." + action.toLowerCase();
        String payload = payload(id, version, reason);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), scope, idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return caseDetail(requireCase(user, id));
        }
        ComplaintCase before = repository.findCaseByIdForUpdate(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("客诉单不存在或不可见"));
        accessService.requireCaseVisible(user, before);
        requireVersion(before.version(), version, "客诉单已变化，请刷新后重试");
        if (!expectedStatuses.contains(before.status())) {
            String allowedLabels = expectedStatuses.stream().map(ComplaintWorkflowService::caseStatusLabel)
                    .collect(java.util.stream.Collectors.joining("、"));
            throw new BusinessException(ErrorCode.CONFLICT,
                    "当前状态不允许" + caseActionLabel(action) + "，仅" + allowedLabels + "状态可执行");
        }
        Long assignee = "ASSIGN".equals(action) ? Long.valueOf(user.userId()) : before.assigneeUserId();
        String resolution = "RESOLVE".equals(action) ? reason : before.resolutionMasked();
        Instant resolvedAt = "RESOLVE".equals(action) ? Instant.now() : before.resolvedAt();
        Instant closedAt = "CLOSE".equals(action) ? Instant.now() : before.closedAt();
        String dismissReason = "DISMISS".equals(action) ? reason : before.dismissReasonMasked();
        if (repository.updateCaseStatus(user.tenantId(), id, version, before.status(), targetStatus, assignee,
                resolution, resolvedAt, closedAt, dismissReason) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "客诉单已变化，请刷新后重试");
        }
        repository.insertCaseHistory(new ComplaintCaseHistory(null, user.tenantId(), id, action, before.status(),
                targetStatus, user.userId(), reason, null));
        ComplaintCase after = repository.findCaseById(user.tenantId(), id).orElseThrow();
        auditService.record(user.tenantId(), user.userId(), "COMPLAINT_CASE_" + action, "COMPLAINT_CASE",
                Long.toString(id), caseSnapshot(before), caseSnapshot(after), reason);
        idempotencyService.recordSuccess(user.tenantId(), scope, idempotencyKey, "COMPLAINT_CASE",
                Long.toString(id), payload);
        return caseDetail(after);
    }

    private ComplaintHit requireHit(CurrentUser user, long id) {
        ComplaintHit hit = repository.findHitById(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("客诉命中不存在或不可见"));
        return accessService.requireHitVisible(user, hit);
    }

    private ComplaintCase requireCase(CurrentUser user, long id) {
        ComplaintCase complaintCase = repository.findCaseById(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("客诉单不存在或不可见"));
        return accessService.requireCaseVisible(user, complaintCase);
    }

    private ComplaintHitDetailResponse hitDetail(ComplaintHit hit) {
        return new ComplaintHitDetailResponse(ComplaintHitResponse.from(hit),
                repository.findHitHistory(hit.tenantId(), hit.id()).stream()
                        .map(ComplaintHitHistoryResponse::from).toList());
    }

    private ComplaintCaseDetailResponse caseDetail(ComplaintCase complaintCase) {
        String script = repository.findPolicy(complaintCase.tenantId())
                .map(ComplaintPolicy::empathyScript).orElse(null);
        return new ComplaintCaseDetailResponse(ComplaintCaseResponse.from(complaintCase),
                repository.findCaseHistory(complaintCase.tenantId(), complaintCase.id()).stream()
                        .map(ComplaintCaseHistoryResponse::from).toList(), script,
                empathyScriptRepository.findActiveScripts(complaintCase.tenantId()).stream()
                        .map(ComplaintEmpathyScriptResponse::from).toList());
    }

    private Map<String, Object> hitReviewSnapshot(ComplaintHit hit) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", hit.status());
        snapshot.put("complaintCaseId", hit.complaintCaseId());
        snapshot.put("reviewReasonMasked", hit.reviewReasonMasked());
        snapshot.put("reviewedBy", hit.reviewedBy());
        snapshot.put("reviewedAt", hit.reviewedAt());
        snapshot.put("version", hit.version());
        return snapshot;
    }

    private Map<String, Object> caseSnapshot(ComplaintCase complaintCase) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", complaintCase.status());
        snapshot.put("assigneeUserId", complaintCase.assigneeUserId());
        snapshot.put("resolutionMasked", complaintCase.resolutionMasked());
        snapshot.put("resolvedAt", complaintCase.resolvedAt());
        snapshot.put("closedAt", complaintCase.closedAt());
        snapshot.put("dismissReasonMasked", complaintCase.dismissReasonMasked());
        snapshot.put("version", complaintCase.version());
        return snapshot;
    }

    private void requireVersion(long actual, long expected, String message) {
        if (actual != expected) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }

    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分页参数无效");
        }
    }

    private long resourceId(IdempotencyRecord record) {
        try {
            return Long.parseLong(record.getResourceId());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果资源无效");
        }
    }

    private void requireResource(IdempotencyRecord record, long id) {
        if (!Long.toString(id).equals(record.getResourceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果与请求资源不一致");
        }
    }

    private String payload(Object... values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等请求序列化失败", ex);
        }
    }

    private static String caseStatusLabel(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "OPEN" -> "待分配";
            case "ASSIGNED" -> "处理中";
            case "RESOLVED" -> "已解决";
            case "CLOSED" -> "已关闭";
            case "DISMISSED" -> "已忽略";
            default -> "其他";
        };
    }

    private static String caseActionLabel(String action) {
        if (action == null) {
            return "当前操作";
        }
        return switch (action) {
            case "ASSIGN" -> "分配处理";
            case "RESOLVE" -> "标记已解决";
            case "CLOSE" -> "关闭客诉单";
            case "DISMISS" -> "忽略客诉单";
            default -> "当前操作";
        };
    }
}
