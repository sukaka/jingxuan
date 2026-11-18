package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.CreateFollowupRequest;
import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;
import com.whale.crm.customer.api.dto.FollowupItemResponse;
import com.whale.crm.customer.api.dto.UpdateFollowupRequest;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.Followup;
import com.whale.crm.customer.domain.FollowupRepository;
import com.whale.crm.followup.application.FollowupPolicyService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.trading.application.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 每日跟进（crm_followup）。每次跟进追加新流水，不覆盖历史；创建后同事务刷新客户 last_followup_at。
 * 新跟进显式下次时间优先，否则按当前策略计算；修改历史记录不回填策略默认值。
 * 修改/软删除权限：创建者本人，或客户同组主管（MANAGER_LEAD），或 ADMIN。
 */
@Service
public class FollowupApplicationService {

    private static final String IDEMPOTENCY_SCOPE = "followup.create";
    private static final String FOLLOWUP_RECORDED_EVENT = "followup.recorded.v1";

    private final FollowupRepository followupRepository;
    private final CustomerRepository customerRepository;
    private final CustomerScopeSupport scopeSupport;
    private final AuditService auditService;
    private final TaskApplicationService taskService;
    private final FollowupPolicyService policyService;
    private final OutboxService outboxService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public FollowupApplicationService(FollowupRepository followupRepository,
                                      CustomerRepository customerRepository,
                                      CustomerScopeSupport scopeSupport,
                                      AuditService auditService,
                                      TaskApplicationService taskService,
                                      FollowupPolicyService policyService,
                                      OutboxService outboxService,
                                      IdempotencyService idempotencyService,
                                      ObjectMapper objectMapper) {
        this.followupRepository = followupRepository;
        this.customerRepository = customerRepository;
        this.scopeSupport = scopeSupport;
        this.auditService = auditService;
        this.taskService = taskService;
        this.policyService = policyService;
        this.outboxService = outboxService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<FollowupItemResponse> search(long tenantId, CurrentUser user, Long customerId,
                                                     int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        if (customerId != null) {
            // 指定客户时先校验可见性，避免借跟进列表探测他人客户
            scopeSupport.requireVisible(tenantId, customerId, user);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        int offset = (page - 1) * size;
        List<FollowupItemResponse> items = followupRepository
                .search(tenantId, query.scopeType(), query.teamIds(), query.selfId(), customerId, offset, size)
                .stream().map(this::toResponse).toList();
        long total = followupRepository.countSearch(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                customerId);
        return new PageResponse<>(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public List<CustomerTimelineItemResponse> timeline(long tenantId, CurrentUser user, long customerId,
                                                        CustomerTimelineCursor cursor) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        List<CustomerTimelineItemResponse> events = new java.util.ArrayList<>();
        int page = 1;
        long total;
        do {
            PageResponse<FollowupItemResponse> result = search(tenantId, user, customerId, page, 100);
            total = result.total();
            result.items().stream().flatMap(item -> followupTimelineEvents(item).stream())
                    .filter(item -> cursor == null || cursor.allows(item)).forEach(events::add);
            page++;
        } while ((long) (page - 1) * 100 < total);
        return events;
    }

    private List<CustomerTimelineItemResponse> followupTimelineEvents(FollowupItemResponse item) {
        Instant occurredAt = item.contactAt() != null ? item.contactAt() : item.createdAt();
        List<CustomerTimelineItemResponse> events = new java.util.ArrayList<>();
        events.add(CustomerTimelineItemResponse.of("FOLLOWUP", item.id(), "FOLLOWUP", occurredAt,
                "跟进记录", item.summary(), Map.of("channelCode", item.channelCode()), item.createdBy()));
        if (item.intentProject() != null && !item.intentProject().isBlank()) {
            events.add(CustomerTimelineItemResponse.of("INTENT", item.id(), "INTENT", occurredAt,
                    "意向项目", item.intentProject(), Map.of("estimatedAmountFen", item.estimatedAmountFen()),
                    item.createdBy()));
        }
        if (item.blockerNote() != null && !item.blockerNote().isBlank()) {
            events.add(CustomerTimelineItemResponse.of("BLOCKER", item.id(), "BLOCKER", occurredAt,
                    "跟进卡点", item.blockerNote(), Map.of("followupId", item.id()), item.createdBy()));
        }
        if (item.nextFollowupAt() != null) {
            events.add(CustomerTimelineItemResponse.of("NEXT_FOLLOWUP", item.id(), "NEXT_FOLLOWUP", occurredAt,
                    "下一步跟进", "计划于 " + item.nextFollowupAt() + " 跟进",
                    Map.of("nextFollowupAt", item.nextFollowupAt()), item.createdBy()));
        }
        return events;
    }

    @Transactional
    public FollowupItemResponse create(long tenantId, CurrentUser user, CreateFollowupRequest request,
                                       String idempotencyKey) {
        Customer customer = scopeSupport.requireVisible(tenantId, request.customerId(), user);
        String key = requireKey(idempotencyKey);
        String payload = canonical(request);
        IdempotencyRecord replay = idempotencyService.lookup(tenantId, IDEMPOTENCY_SCOPE, key, payload).orElse(null);
        if (replay != null) {
            if (!"FOLLOWUP".equals(replay.getResourceType())) {
                throw new BusinessException(ErrorCode.CONFLICT, "幂等结果不属于跟进记录");
            }
            Followup stored = followupRepository.findById(tenantId, Long.parseLong(replay.getResourceId()))
                    .filter(value -> !value.isDeleted() && Objects.equals(value.getCustomerId(), request.customerId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "幂等结果与跟进请求不一致"));
            return toResponse(stored, null);
        }
        Instant contactAt = request.contactAt() != null ? request.contactAt() : Instant.now();

        Followup followup = new Followup();
        followup.setTenantId(tenantId);
        followup.setCustomerId(customer.getId());
        followup.setOwnerStaffId(customer.getOwnerStaffId());
        followup.setChannelCode(request.channelCode());
        followup.setContactAt(contactAt);
        followup.setSummary(blankToNull(request.summary()));
        followup.setIntentProject(blankToNull(request.intentProject()));
        followup.setEstimatedAmountFen(request.estimatedAmountFen() != null ? request.estimatedAmountFen() : 0L);
        followup.setNextFollowupAt(policyService.nextFollowupAt(
                tenantId, customer.getIntentLevel(), contactAt, request.nextFollowupAt()));
        followup.setBlockerNote(blankToNull(request.blockerNote()));
        followup.setCreatedBy(user.userId());
        followupRepository.insert(followup);
        customerRepository.updateLastFollowupAt(tenantId, customer.getId(), contactAt);

        Followup persisted = followupRepository.findById(tenantId, followup.getId()).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_FOLLOWUP_CREATE, "FOLLOWUP",
                Long.toString(followup.getId()), null, followupSnapshot(persisted), null);
        outboxService.append(tenantId, FOLLOWUP_RECORDED_EVENT + ":" + persisted.getId(), FOLLOWUP_RECORDED_EVENT,
                "followup", Long.toString(persisted.getId()), Map.of(
                        "followupId", persisted.getId(), "customerId", persisted.getCustomerId(),
                        "sourceVersion", persisted.getVersion(), "staffUserId", persisted.getOwnerStaffId(),
                        "contactAt", persisted.getContactAt()));
        taskService.syncFromFollowup(tenantId, user, persisted, customer);
        idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE, key, "FOLLOWUP",
                Long.toString(persisted.getId()), payload);
        return toResponse(persisted, null);
    }

    @Transactional
    public FollowupItemResponse update(long tenantId, CurrentUser user, long followupId,
                                       UpdateFollowupRequest request) {
        Followup existing = followupRepository.findById(tenantId, followupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见"));
        if (existing.isDeleted()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见");
        }
        Customer customer = customerRepository.findById(tenantId, existing.getCustomerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见"));
        if (!scopeSupport.isVisible(scopeSupport.scope(user), user, customer)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见");
        }
        requireEditable(user, existing, customer);
        if (!Objects.equals(existing.getVersion(), request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }

        Instant previousNextFollowupAt = existing.getNextFollowupAt();
        Map<String, Object> beforeSnapshot = followupSnapshot(existing);
        existing.setChannelCode(request.channelCode() != null ? request.channelCode() : existing.getChannelCode());
        existing.setContactAt(request.contactAt() != null ? request.contactAt() : existing.getContactAt());
        existing.setSummary(request.summary() != null ? blankToNull(request.summary()) : existing.getSummary());
        existing.setIntentProject(request.intentProject() != null ? blankToNull(request.intentProject()) : existing.getIntentProject());
        existing.setEstimatedAmountFen(request.estimatedAmountFen() != null ? request.estimatedAmountFen() : existing.getEstimatedAmountFen());
        existing.setNextFollowupAt(request.nextFollowupAt() != null ? request.nextFollowupAt() : existing.getNextFollowupAt());
        existing.setBlockerNote(request.blockerNote() != null ? blankToNull(request.blockerNote()) : existing.getBlockerNote());
        int rows = followupRepository.update(existing);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        Followup after = followupRepository.findById(tenantId, followupId).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_FOLLOWUP_UPDATE, "FOLLOWUP",
                Long.toString(followupId), beforeSnapshot, followupSnapshot(after), null);
        if (!Objects.equals(previousNextFollowupAt, after.getNextFollowupAt())) {
            taskService.syncFromFollowup(tenantId, user, after, customer);
        }
        return toResponse(after, null);
    }

    @Transactional
    public void delete(long tenantId, CurrentUser user, long followupId, long version) {
        Followup existing = followupRepository.findById(tenantId, followupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见"));
        if (existing.isDeleted()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见");
        }
        Customer customer = customerRepository.findById(tenantId, existing.getCustomerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见"));
        if (!scopeSupport.isVisible(scopeSupport.scope(user), user, customer)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见");
        }
        requireEditable(user, existing, customer);
        if (existing.getVersion() != version) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        Map<String, Object> beforeSnapshot = followupSnapshot(existing);
        existing.setDeleted(true);
        if (followupRepository.update(existing) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        auditService.record(tenantId, user.userId(), "FOLLOWUP_DELETE", "FOLLOWUP",
                Long.toString(followupId), beforeSnapshot, followupSnapshot(existing), "误录记录软删除");
    }

    /**
     * 创建者本人 / 客户同组 MANAGER_LEAD / ADMIN 可改。
     */
    private void requireEditable(CurrentUser user, Followup followup, Customer customer) {
        if (Objects.equals(followup.getCreatedBy(), user.userId()) || user.roles().contains("ADMIN")) {
            return;
        }
        boolean teamLead = user.roles().contains("MANAGER_LEAD") && user.teamId() != null
                && Objects.equals(user.teamId(), customer.getTeamId());
        if (!teamLead) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅创建者或同组主管可修改跟进记录");
        }
    }

    private Map<String, Object> followupSnapshot(Followup followup) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", followup.getId());
        snapshot.put("customerId", followup.getCustomerId());
        snapshot.put("ownerStaffId", followup.getOwnerStaffId());
        snapshot.put("channelCode", followup.getChannelCode());
        snapshot.put("contactAt", followup.getContactAt());
        snapshot.put("intentProject", followup.getIntentProject());
        snapshot.put("estimatedAmountFen", followup.getEstimatedAmountFen());
        snapshot.put("nextFollowupAt", followup.getNextFollowupAt());
        snapshot.put("createdBy", followup.getCreatedBy());
        snapshot.put("deleted", followup.isDeleted());
        return snapshot;
    }

    private FollowupItemResponse toResponse(FollowupRepository.FollowupListRow row) {
        return new FollowupItemResponse(row.id(), row.customerId(), row.customerName(), row.ownerStaffId(),
                row.ownerStaffName(), row.channelCode(), row.contactAt(), row.summary(), row.intentProject(), row.estimatedAmountFen(),
                row.nextFollowupAt(), row.blockerNote(), row.createdBy(), row.createdAt(), row.version());
    }

    private FollowupItemResponse toResponse(Followup followup, String customerName) {
        return new FollowupItemResponse(followup.getId(), followup.getCustomerId(), customerName,
                followup.getOwnerStaffId(), null, followup.getChannelCode(), followup.getContactAt(),
                followup.getSummary(), followup.getIntentProject(), followup.getEstimatedAmountFen(),
                followup.getNextFollowupAt(), followup.getBlockerNote(), followup.getCreatedBy(),
                followup.getCreatedAt(), followup.getVersion());
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "创建跟进必须提供1至128字符的幂等键");
        }
        return key.strip();
    }

    private String canonical(CreateFollowupRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception ex) {
            throw new IllegalStateException("跟进创建请求规范化失败", ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
