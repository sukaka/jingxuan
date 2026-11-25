package com.whale.crm.customer.tier.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.application.CustomerTimelineCursor;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.tier.api.dto.CustomerTierAdjustmentRequest;
import com.whale.crm.customer.tier.api.dto.CustomerTierEvaluationResponse;
import com.whale.crm.customer.tier.api.dto.CustomerTierHistoryResponse;
import com.whale.crm.customer.tier.api.dto.CustomerTierRecalculateRequest;
import com.whale.crm.customer.tier.api.dto.CustomerTierViewResponse;
import com.whale.crm.customer.tier.domain.CustomerTierHistory;
import com.whale.crm.customer.tier.domain.CustomerTierRepository;
import com.whale.crm.customer.tier.domain.CustomerTierRule;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CustomerTierCustomerService {
    private static final String RECALCULATE_SCOPE = "customer:tier:recalculate";
    private static final String ADJUST_SCOPE = "customer:tier:adjust";

    private final CustomerTierRepository repository;
    private final CustomerRepository customerRepository;
    private final CustomerScopeSupport scopeSupport;
    private final CustomerTierRecalculationService recalculationService;
    private final CustomerTierReviewService reviewService;
    private final CustomerTierConfigMapper configMapper;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public CustomerTierCustomerService(CustomerTierRepository repository, CustomerRepository customerRepository,
                                       CustomerScopeSupport scopeSupport,
                                       CustomerTierRecalculationService recalculationService,
                                       CustomerTierReviewService reviewService, CustomerTierConfigMapper configMapper,
                                       IdempotencyService idempotencyService, AuditService auditService,
                                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.customerRepository = customerRepository;
        this.scopeSupport = scopeSupport;
        this.recalculationService = recalculationService;
        this.reviewService = reviewService;
        this.configMapper = configMapper;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CustomerTierViewResponse view(long tenantId, CurrentUser user, long customerId) {
        Customer customer = scopeSupport.requireVisible(tenantId, customerId, user);
        return view(customer);
    }

    @Transactional(readOnly = true)
    public List<CustomerTierEvaluationResponse> snapshots(long tenantId, CurrentUser user, long customerId,
                                                          int limit) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        return repository.findSnapshots(tenantId, customerId, limit).stream().map(configMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerTierHistoryResponse> history(long tenantId, CurrentUser user, long customerId, int limit) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        return repository.findHistory(tenantId, customerId, limit).stream().map(configMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerTimelineItemResponse> timeline(long tenantId, CurrentUser user, long customerId,
                                                        CustomerTimelineCursor cursor, int limit) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        int safeLimit = Math.max(1, limit);
        CustomerTimelineCursor.SqlCursor sqlCursor = CustomerTimelineCursor.sqlCursor(cursor, "TIER");
        return repository.findTimelineHistory(tenantId, customerId, sqlCursor.modeName(),
                        sqlCursor.occurredAt(), sqlCursor.sourceId(), safeLimit).stream()
                .map(item -> {
                    Map<String, Object> details = new java.util.LinkedHashMap<>();
                    details.put("fromTierCode", item.fromTierCode());
                    details.put("toTierCode", item.toTierCode());
                    details.put("changeType", item.changeType());
                    Instant occurredAt = item.effectiveAt() != null ? item.effectiveAt() : item.createdAt();
                    return CustomerTimelineItemResponse.of("TIER", item.id(), "TIER", occurredAt,
                            "客户等级变更", item.reason(), details, item.actorUserId());
                })
                .filter(item -> cursor == null || cursor.allows(item))
                .limit(safeLimit)
                .toList();
    }

    @Transactional
    public CustomerTierEvaluationResponse recalculate(long tenantId, CurrentUser user, long customerId,
                                                      CustomerTierRecalculateRequest request,
                                                      String idempotencyKey) {
        requireKey(idempotencyKey);
        Customer visible = scopeSupport.requireVisibleForUpdate(tenantId, customerId, user);
        String payload = customerId + "|" + canonical(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, RECALCULATE_SCOPE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            long snapshotId = Long.parseLong(replay.orElseThrow().getResourceId());
            return repository.findSnapshot(tenantId, snapshotId).map(configMapper::toResponse)
                    .orElseThrow(() -> notFound("原重算评估记录不存在"));
        }
        String recalculationKey = "manual:" + customerId + ":" + idempotencyKey.strip();
        Optional<CustomerTierEvaluationResponse> snapshotReplay = repository
                .findSnapshotByKey(tenantId, recalculationKey).map(configMapper::toResponse);
        if (snapshotReplay.isPresent()) {
            return snapshotReplay.orElseThrow();
        }
        if (visible.getVersion().longValue() != request.version()) {
            throw conflict("客户已被他人修改，请刷新后重试");
        }
        Instant asOf = request.asOf() == null ? Instant.now() : request.asOf();
        CustomerTierEvaluationResponse response = recalculationService.recalculate(tenantId, customerId, asOf,
                "MANUAL", recalculationKey, user.userId(), request.version());
        idempotencyService.recordSuccess(tenantId, RECALCULATE_SCOPE, idempotencyKey, "CUSTOMER_TIER_SNAPSHOT",
                Long.toString(response.snapshotId()), payload);
        return response;
    }

    @Transactional
    public CustomerTierViewResponse adjust(long tenantId, CurrentUser user, long customerId,
                                           CustomerTierAdjustmentRequest request, String idempotencyKey) {
        requireKey(idempotencyKey);
        Customer customer = scopeSupport.requireVisibleForUpdate(tenantId, customerId, user);
        String payload = customerId + "|" + canonical(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, ADJUST_SCOPE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            return view(tenantId, user, customerId);
        }
        if (customer.getVersion().longValue() != request.version()) {
            throw conflict("客户已被他人修改，请刷新后重试");
        }
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty() || reason.length() > 1000) {
            throw validation("人工调整理由不能为空且最长 1000 字符");
        }
        CustomerTierRule active = repository.findActiveRule(tenantId)
                .orElseThrow(() -> conflict("租户尚未激活客户分级规则"));
        boolean knownTier = configMapper.levels(active).stream()
                .anyMatch(level -> level.tierCode().equals(request.tierCode()));
        if (!knownTier) {
            throw validation("目标等级不属于当前已启用的分级规则");
        }
        Instant now = Instant.now();
        if (repository.updateTier(tenantId, customerId, request.version(), request.tierCode(),
                request.lock(), request.lock() ? reason : null) != 1) {
            throw conflict("客户已被他人修改，请刷新后重试");
        }
        reviewService.markCustomerChanged(tenantId, customerId, user.userId());
        repository.insertHistory(new CustomerTierHistory(null, tenantId, customerId, null, null, null,
                customer.getTierCode(), request.tierCode(), "MANUAL_ADJUST", user.userId(), reason, now, null));
        auditService.record(tenantId, user.userId(), "CUSTOMER_TIER_MANUAL_ADJUST", "CUSTOMER",
                Long.toString(customerId), Map.of("tierCode", nullToEmpty(customer.getTierCode()),
                        "locked", customer.isTierLocked()),
                Map.of("tierCode", request.tierCode(), "locked", request.lock()), reason);
        idempotencyService.recordSuccess(tenantId, ADJUST_SCOPE, idempotencyKey, "CUSTOMER",
                Long.toString(customerId), payload);
        Customer after = customerRepository.findById(tenantId, customerId).orElseThrow();
        return view(after);
    }

    private CustomerTierViewResponse view(Customer customer) {
        long tenantId = customer.getTenantId();
        long customerId = customer.getId();
        Long activeRuleId = repository.findActiveRule(tenantId).map(CustomerTierRule::id).orElse(null);
        CustomerTierEvaluationResponse latest = repository.findLatestSnapshot(tenantId, customerId)
                .map(configMapper::toResponse).orElse(null);
        return new CustomerTierViewResponse(customerId, customer.getTierCode(), customer.isTierLocked(),
                customer.getTierLockReason(), customer.getVersion(), activeRuleId, latest,
                repository.findPending(tenantId, customerId).map(configMapper::toResponse).orElse(null));
    }

    private String canonical(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw validation("请求无法序列化");
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw validation("请求标识不能为空且最长 128 字符");
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
