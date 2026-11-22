package com.whale.crm.customer.tier.application;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.tier.api.dto.CustomerTierReviewRequest;
import com.whale.crm.customer.tier.api.dto.CustomerTierReviewResponse;
import com.whale.crm.customer.tier.domain.CustomerTierDowngradeReview;
import com.whale.crm.customer.tier.domain.CustomerTierHistory;
import com.whale.crm.customer.tier.domain.CustomerTierMetricSnapshot;
import com.whale.crm.customer.tier.domain.CustomerTierRepository;
import com.whale.crm.customer.tier.domain.CustomerTierRule;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class CustomerTierReviewService {
    private static final Set<String> STATUSES = Set.of(CustomerTierDowngradeReview.PENDING,
            CustomerTierDowngradeReview.APPROVED, CustomerTierDowngradeReview.REJECTED,
            CustomerTierDowngradeReview.STALE);

    private final CustomerTierRepository repository;
    private final CustomerRepository customerRepository;
    private final CustomerScopeSupport scopeSupport;
    private final CustomerTierConfigMapper configMapper;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;

    public CustomerTierReviewService(CustomerTierRepository repository, CustomerRepository customerRepository,
                                     CustomerScopeSupport scopeSupport, CustomerTierConfigMapper configMapper,
                                     IdempotencyService idempotencyService, AuditService auditService) {
        this.repository = repository;
        this.customerRepository = customerRepository;
        this.scopeSupport = scopeSupport;
        this.configMapper = configMapper;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerTierReviewResponse> list(long tenantId, CurrentUser user, String status,
                                                         int page, int size) {
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw validation("复核状态无效");
        }
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        String normalized = status == null || status.isBlank() ? null : status;
        List<CustomerTierReviewResponse> items = repository.searchReviews(tenantId, query.scopeType(),
                        query.teamIds(), query.selfId(), normalized, (page - 1) * size, size)
                .stream().map(configMapper::toResponse).toList();
        long total = repository.countReviews(tenantId, query.scopeType(), query.teamIds(), query.selfId(), normalized);
        return new PageResponse<>(items, total, page, size);
    }

    @Transactional
    public CustomerTierReviewResponse approve(long tenantId, CurrentUser user, long reviewId,
                                              CustomerTierReviewRequest request, String idempotencyKey) {
        return review(tenantId, user, reviewId, request, idempotencyKey, true);
    }

    @Transactional
    public CustomerTierReviewResponse reject(long tenantId, CurrentUser user, long reviewId,
                                             CustomerTierReviewRequest request, String idempotencyKey) {
        return review(tenantId, user, reviewId, request, idempotencyKey, false);
    }

    @Transactional
    public void markCustomerChanged(long tenantId, long customerId, Long actorId) {
        int changed = repository.markPendingStale(tenantId, customerId, "客户版本已变化", Instant.now());
        if (changed > 0) {
            auditService.record(tenantId, actorId, "CUSTOMER_TIER_REVIEW_STALE", "CUSTOMER",
                    Long.toString(customerId), Map.of("status", CustomerTierDowngradeReview.PENDING),
                    Map.of("status", CustomerTierDowngradeReview.STALE), "客户版本已变化");
        }
    }

    private CustomerTierReviewResponse review(long tenantId, CurrentUser user, long reviewId,
                                              CustomerTierReviewRequest request, String idempotencyKey,
                                              boolean approve) {
        requireKey(idempotencyKey);
        if (request.reason() == null || request.reason().isBlank()) {
            throw validation("复核理由不能为空");
        }
        String action = approve ? "approve" : "reject";
        String scope = "customer:tier:review:" + action;
        String payload = reviewId + "|" + request.version() + "|" + request.reason().strip();
        CustomerTierDowngradeReview initial = repository.findReview(tenantId, reviewId)
                .orElseThrow(() -> notFound("降级复核单不存在或不可见"));
        Customer customer = customerRepository.findByIdForUpdate(tenantId, initial.customerId())
                .orElseThrow(() -> notFound("客户不存在或不可见"));
        if (!scopeSupport.isVisible(scopeSupport.scope(user), user, customer)) {
            throw notFound("降级复核单不存在或不可见");
        }
        CustomerTierDowngradeReview review = repository.findReviewForUpdate(tenantId, reviewId)
                .orElseThrow(() -> notFound("降级复核单不存在或不可见"));
        if (!Objects.equals(review.customerId(), customer.getId())) {
            throw notFound("降级复核单不存在或不可见");
        }
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, scope, idempotencyKey, payload);
        if (replay.isPresent()) {
            return configMapper.toResponse(requireReview(tenantId, reviewId));
        }
        if (review.version() != request.version() || !CustomerTierDowngradeReview.PENDING.equals(review.status())) {
            throw conflict("复核单已处理或版本已变化，请刷新后重试");
        }
        requireFresh(review, customer);
        Instant now = Instant.now();
        String targetStatus = approve ? CustomerTierDowngradeReview.APPROVED : CustomerTierDowngradeReview.REJECTED;
        if (approve) {
            if (repository.updateTier(tenantId, customer.getId(), customer.getVersion(), review.toTierCode(),
                    false, null) != 1) {
                throw conflict("客户版本已变化，降级未生效");
            }
            repository.insertHistory(new CustomerTierHistory(null, tenantId, customer.getId(), review.ruleId(),
                    review.snapshotId(), review.id(), review.fromTierCode(), review.toTierCode(),
                    "DOWNGRADE_APPROVED", user.userId(), request.reason().strip(), now, null));
        }
        if (repository.updateReviewStatus(tenantId, reviewId, request.version(), CustomerTierDowngradeReview.PENDING,
                targetStatus, user.userId(), request.reason().strip(), now, approve ? now : null) != 1) {
            throw conflict("复核单已处理或版本已变化，请刷新后重试");
        }
        CustomerTierDowngradeReview after = requireReview(tenantId, reviewId);
        auditService.record(tenantId, user.userId(), approve ? "CUSTOMER_TIER_DOWNGRADE_APPROVE"
                        : "CUSTOMER_TIER_DOWNGRADE_REJECT", "CUSTOMER_TIER_REVIEW", Long.toString(reviewId),
                configMapper.toResponse(review), configMapper.toResponse(after), request.reason().strip());
        idempotencyService.recordSuccess(tenantId, scope, idempotencyKey, "CUSTOMER_TIER_REVIEW",
                Long.toString(reviewId), payload);
        return configMapper.toResponse(after);
    }

    private void requireFresh(CustomerTierDowngradeReview review, Customer customer) {
        if (customer.isDeleted() || customer.getVersion() != review.baseCustomerVersion()
                || !Objects.equals(customer.getTierCode(), review.fromTierCode()) || customer.isTierLocked()) {
            throw conflict("客户版本或等级已变化，旧复核单已失效");
        }
        CustomerTierRule active = repository.findActiveRule(review.tenantId())
                .orElseThrow(() -> conflict("当前没有 ACTIVE 分级规则，旧复核单已失效"));
        if (active.id() != review.ruleId()) {
            throw conflict("分级规则版本已变化，旧复核单已失效");
        }
        CustomerTierMetricSnapshot latest = repository.findLatestSnapshot(review.tenantId(), review.customerId())
                .orElseThrow(() -> conflict("指标快照不存在，旧复核单已失效"));
        if (!Objects.equals(latest.id(), review.snapshotId())) {
            throw conflict("指标快照版本已变化，旧复核单已失效");
        }
    }

    private CustomerTierDowngradeReview requireReview(long tenantId, long reviewId) {
        return repository.findReview(tenantId, reviewId)
                .orElseThrow(() -> notFound("降级复核单不存在或不可见"));
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw validation("Idempotency-Key 不能为空且最长 128 字符");
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }
}
