package com.whale.crm.fulfillment.application;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.fulfillment.api.dto.ReversalDetail;
import com.whale.crm.fulfillment.api.dto.ReviewReversalRequest;
import com.whale.crm.fulfillment.domain.FulfillmentRepository;
import com.whale.crm.fulfillment.domain.FulfillmentScope;
import com.whale.crm.fulfillment.domain.RedemptionLine;
import com.whale.crm.fulfillment.domain.RedemptionReversal;
import com.whale.crm.fulfillment.domain.RedemptionReversalHistory;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.security.CurrentUser;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReversalApplicationService {
    private static final String REVIEW_SCOPE = "redemption.reversal.review";

    private final FulfillmentRepository repository;
    private final FulfillmentSupport support;
    private final CustomerScopeSupport scopeSupport;
    private final FulfillmentTradingOperations tradingOperations;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public ReversalApplicationService(FulfillmentRepository repository, FulfillmentSupport support,
                                      CustomerScopeSupport scopeSupport, FulfillmentTradingOperations tradingOperations,
                                      OutboxService outboxService, AuditService auditService,
                                      PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.support = support;
        this.scopeSupport = scopeSupport;
        this.tradingOperations = tradingOperations;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<RedemptionReversal> search(long tenantId, CurrentUser user, Long customerId,
                                                   Long institutionId, String status, int page, int size) {
        FulfillmentScope scope = support.scope(tenantId, user);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchReversals(tenantId, scope, customerId, institutionId,
                        status, offset, size),
                repository.countReversals(tenantId, scope, customerId, institutionId, status), page, size);
    }

    @Transactional(readOnly = true)
    public ReversalDetail detail(long tenantId, CurrentUser user, long reversalId) {
        return support.reversalDetail(tenantId, user, reversalId);
    }

    public ReversalDetail review(long tenantId, CurrentUser user, long reversalId, String action,
                                 ReviewReversalRequest request, String idempotencyKey) {
        requireReviewer(user);
        RedemptionReversal visible = support.requireReversalVisible(tenantId, user, reversalId);
        requireNotApplicant(user, visible);
        String key = support.requireKey(idempotencyKey);
        String payload = support.canonical(new ReviewCanonical(reversalId, action, request.version(), request.reason()));
        IdempotencyRecord replay = support.lookup(tenantId, REVIEW_SCOPE, key, payload);
        if (replay != null) {
            return support.reversalDetail(tenantId, user, reversalId);
        }
        try {
            return transactionTemplate.execute(status -> doReview(tenantId, user, reversalId, action,
                    request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayReview(tenantId, user, reversalId, key, payload));
        }
    }

    private ReversalDetail doReview(long tenantId, CurrentUser user, long reversalId, String action,
                                    ReviewReversalRequest request, String key, String payload) {
        requireReviewer(user);
        RedemptionReversal before = repository.findReversalForUpdate(tenantId, reversalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "冲正申请不存在"));
        scopeSupport.requireVisible(tenantId, before.customerId(), user);
        requireNotApplicant(user, before);
        IdempotencyRecord replay = support.lookup(tenantId, REVIEW_SCOPE, key, payload);
        if (replay != null) {
            return support.reversalDetail(tenantId, user, reversalId);
        }
        if (!"REQUESTED".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "冲正申请已审核，不能重复审批");
        }
        if (!before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "冲正申请版本已变化，请刷新后重试");
        }
        if (!"approve".equals(action) && !"reject".equals(action)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未知冲正审核操作");
        }
        if ("approve".equals(action)) {
            applyCorrections(tenantId, user, before);
        }
        String toStatus = "approve".equals(action) ? "APPROVED" : "REJECTED";
        Long activeRedemptionId = "approve".equals(action) ? before.redemptionId() : null;
        if (repository.updateReversalStatus(tenantId, reversalId, request.version(), "REQUESTED", toStatus,
                user.userId(), Instant.now(), request.reason(), key, activeRedemptionId) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "冲正申请已被其他审核人处理");
        }
        repository.insertReversalHistory(new RedemptionReversalHistory(null, reversalId, "REQUESTED",
                toStatus, user.userId(), request.reason(), null));
        String eventAction = "approve".equals(action) ? "approved" : "rejected";
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("reversalId", reversalId);
        eventPayload.put("redemptionId", before.redemptionId());
        eventPayload.put("status", toStatus);
        eventPayload.put("reviewedBy", user.userId());
        if ("approve".equals(action)) {
            List<Map<String, Object>> corrections = tradingOperations.corrections(tenantId, reversalId).stream()
                    .map(correction -> Map.<String, Object>of("ledgerId", correction.ledgerId(),
                            "entitlementId", correction.entitlementId(), "projectId", correction.projectId(),
                            "deltaCount", correction.deltaCount(), "sourceRef", correction.sourceRef()))
                    .toList();
            eventPayload.put("corrections", corrections);
        }
        outboxService.append(tenantId, "redemption.reversal." + eventAction + ".v1:" + reversalId,
                "redemption.reversal." + eventAction + ".v1", "redemption_reversal", Long.toString(reversalId),
                eventPayload);
        ReversalDetail after = support.reversalDetail(tenantId, user, reversalId);
        auditService.record(tenantId, user.userId(), "REDEMPTION_REVERSAL_" + action.toUpperCase(),
                "REDEMPTION_REVERSAL", Long.toString(reversalId), before, after, request.reason());
        support.recordSuccess(tenantId, REVIEW_SCOPE, key, "REDEMPTION_REVERSAL", reversalId, payload);
        return after;
    }

    private void applyCorrections(long tenantId, CurrentUser user, RedemptionReversal reversal) {
        List<RedemptionLine> lines = repository.findRedemptionLines(tenantId, reversal.redemptionId()).stream()
                .sorted(Comparator.comparingLong(RedemptionLine::entitlementId)).toList();
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "原核销缺少项目明细，不能冲正");
        }
        Map<Long, FulfillmentEntitlementView> locked = new HashMap<>();
        for (RedemptionLine line : lines) {
            locked.put(line.entitlementId(), tradingOperations.lockReversible(tenantId, line.entitlementId(),
                    reversal.customerId(), line.projectId(), line.id().toString(), line.count()));
        }
        for (RedemptionLine line : lines) {
            tradingOperations.appendReversal(tenantId, user.userId(), locked.get(line.entitlementId()), line.count(),
                    reversal.id().toString(), "redemption.reversal:" + reversal.id() + ":" + line.id());
        }
    }

    private ReversalDetail replayReview(long tenantId, CurrentUser user, long reversalId,
                                        String key, String payload) {
        requireReviewer(user);
        RedemptionReversal reversal = repository.findReversalForUpdate(tenantId, reversalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "冲正申请不存在"));
        scopeSupport.requireVisible(tenantId, reversal.customerId(), user);
        requireNotApplicant(user, reversal);
        IdempotencyRecord record = support.lookup(tenantId, REVIEW_SCOPE, key, payload);
        if (record == null || support.resourceId(record) != reversalId) {
            throw new BusinessException(ErrorCode.CONFLICT, "冲正审核冲突且没有匹配的幂等结果");
        }
        return support.reversalDetail(tenantId, user, reversalId);
    }

    private void requireReviewer(CurrentUser user) {
        if (user.isInstitutionUser() || !user.permissions().contains("redemption:approve")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号不是授权冲正审核人");
        }
    }

    private void requireNotApplicant(CurrentUser user, RedemptionReversal reversal) {
        if (reversal.requestedBy().equals(user.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "冲正申请人不能审核本人申请");
        }
    }

    private record ReviewCanonical(long reversalId, String action, long version, String reason) {
    }
}
