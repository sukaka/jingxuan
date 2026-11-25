package com.whale.crm.customer.tier.application;

import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.tier.api.dto.CustomerTierEvaluationResponse;
import com.whale.crm.customer.tier.domain.CustomerTierDowngradeReview;
import com.whale.crm.customer.tier.domain.CustomerTierHistory;
import com.whale.crm.customer.tier.domain.CustomerTierMetricSnapshot;
import com.whale.crm.customer.tier.domain.CustomerTierMetrics;
import com.whale.crm.customer.tier.domain.CustomerTierRepository;
import com.whale.crm.customer.tier.domain.CustomerTierRule;
import com.whale.crm.customer.tier.domain.TierLevelDefinition;
import com.whale.crm.customer.tier.domain.TierWindow;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationRequest;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class CustomerTierRecalculationService {
    private final CustomerTierRepository repository;
    private final CustomerRepository customerRepository;
    private final TierEvaluationService evaluationService;
    private final CustomerTierConfigMapper configMapper;
    private final NotificationRequestService notificationService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final WecomArchiveService wecomArchiveService;

    public CustomerTierRecalculationService(CustomerTierRepository repository, CustomerRepository customerRepository,
                                            TierEvaluationService evaluationService,
                                            CustomerTierConfigMapper configMapper,
                                            NotificationRequestService notificationService,
                                            UserRepository userRepository, AuditService auditService,
                                            WecomArchiveService wecomArchiveService) {
        this.repository = repository;
        this.customerRepository = customerRepository;
        this.evaluationService = evaluationService;
        this.configMapper = configMapper;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.wecomArchiveService = wecomArchiveService;
    }

    @Transactional
    public CustomerTierEvaluationResponse recalculate(long tenantId, long customerId, Instant asOf,
                                                      String sourceTrigger, String recalculationKey, Long actorId) {
        return recalculate(tenantId, customerId, asOf, sourceTrigger, recalculationKey, actorId, null);
    }

    @Transactional
    public CustomerTierEvaluationResponse recalculate(long tenantId, long customerId, Instant asOf,
                                                      String sourceTrigger, String recalculationKey, Long actorId,
                                                      Long expectedCustomerVersion) {
        Customer customer = customerRepository.findByIdForUpdate(tenantId, customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在"));
        if (customer.isDeleted()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在");
        }
        String key = stableKey(recalculationKey);
        Optional<CustomerTierMetricSnapshot> replay = repository.findSnapshotByKey(tenantId, key);
        if (replay.isPresent()) {
            return configMapper.toResponse(replay.orElseThrow());
        }
        if (expectedCustomerVersion != null
                && customer.getVersion().longValue() != expectedCustomerVersion.longValue()) {
            throw new BusinessException(ErrorCode.CONFLICT, "客户已被他人修改，请刷新后重试");
        }
        CustomerTierRule rule = repository.findActiveRuleForUpdate(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "租户尚未激活客户分级规则"));
        TierWindow window = evaluationService.window(asOf, ZoneId.of(rule.zoneId()));
        String archiveScope = wecomArchiveService.activeArchiveScope(tenantId);
        CustomerTierMetrics values = repository.loadMetrics(tenantId, customerId,
                window.startInclusive(), window.endExclusive(), archiveScope);
        List<TierLevelDefinition> levels = configMapper.levels(rule);
        TierEvaluation evaluation = evaluationService.evaluate(levels, configMapper.metrics(rule), values);
        CustomerTierMetricSnapshot candidate = new CustomerTierMetricSnapshot(null, tenantId, customerId, rule.id(),
                key, sourceTrigger, asOf, window.startInclusive(), window.endExclusive(), rule.zoneId(),
                values.paymentAmountFen(), values.paidAmountFen(), values.refundAmountFen(), values.orderCount(),
                values.manualFollowupCount(), values.archivedMessageCount(), values.communicationCount(),
                values.communicationComplete(), values.actualConsumptionFen(), evaluation.score(),
                evaluation.suggestedTierCode(), null);
        CustomerTierMetricSnapshot snapshot;
        try {
            snapshot = repository.insertSnapshot(candidate);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "相同重算请求正在处理，请查询结果");
        }
        auditService.record(tenantId, actorId, "CUSTOMER_TIER_METRIC_SNAPSHOT", "CUSTOMER",
                Long.toString(customerId), null, configMapper.toResponse(snapshot), null);

        CustomerTierDowngradeReview pending = repository.findPendingForUpdate(tenantId, customerId).orElse(null);
        if (pending != null) {
            repository.markPendingStale(tenantId, customerId, "指标快照版本已变化", asOf);
        }
        if (customer.isTierLocked() || evaluation.suggestedTierCode() == null
                || evaluation.suggestedTierCode().equals(customer.getTierCode())) {
            return configMapper.toResponse(snapshot);
        }
        TierLevelDefinition currentLevel = findLevel(levels, customer.getTierCode());
        TierLevelDefinition targetLevel = findLevel(levels, evaluation.suggestedTierCode());
        if (customer.getTierCode() != null && currentLevel == null) {
            return configMapper.toResponse(snapshot);
        }
        if (currentLevel == null || targetLevel.sortNo() < currentLevel.sortNo()) {
            applyUpgrade(customer, rule, snapshot, targetLevel.tierCode(), actorId, asOf);
        } else {
            createReview(customer, rule, snapshot, targetLevel.tierCode(), actorId, asOf);
        }
        return configMapper.toResponse(snapshot);
    }

    private void applyUpgrade(Customer customer, CustomerTierRule rule, CustomerTierMetricSnapshot snapshot,
                              String targetTier, Long actorId, Instant now) {
        String previous = customer.getTierCode();
        if (repository.updateTier(customer.getTenantId(), customer.getId(), customer.getVersion(), targetTier,
                false, null) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "客户等级更新版本冲突");
        }
        repository.insertHistory(new CustomerTierHistory(null, customer.getTenantId(), customer.getId(), rule.id(),
                snapshot.id(), null, previous, targetTier, "AUTO_UPGRADE", actorId,
                "实际消费金额达到更高等级", now, null));
        auditService.record(customer.getTenantId(), actorId, "CUSTOMER_TIER_AUTO_UPGRADE", "CUSTOMER",
                Long.toString(customer.getId()), Map.of("tierCode", nullToEmpty(previous)),
                Map.of("tierCode", targetTier, "snapshotId", snapshot.id()), "实际消费金额自动升级");
    }

    private void createReview(Customer customer, CustomerTierRule rule, CustomerTierMetricSnapshot snapshot,
                              String targetTier, Long actorId, Instant now) {
        CustomerTierDowngradeReview candidate = new CustomerTierDowngradeReview(null, customer.getTenantId(),
                customer.getId(), rule.id(), snapshot.id(), customer.getTierCode(), targetTier,
                customer.getVersion(), CustomerTierDowngradeReview.PENDING, actorId, null, null,
                null, null, null, null, 0);
        CustomerTierDowngradeReview review;
        try {
            review = repository.insertReview(candidate);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "该客户已有待复核降级单");
        }
        auditService.record(customer.getTenantId(), actorId, "CUSTOMER_TIER_DOWNGRADE_REQUEST", "CUSTOMER_TIER_REVIEW",
                Long.toString(review.id()), null, configMapper.toResponse(review), "自动降级须人工批准");
        Optional<SysUser> recipient = userRepository.findById(customer.getTenantId(), customer.getOwnerStaffId());
        if (recipient.isPresent() && "ACTIVE".equals(recipient.orElseThrow().getStatus())) {
            notificationService.request(new NotificationRequest(customer.getTenantId(), customer.getOwnerStaffId(),
                    "CUSTOMER_TIER_DOWNGRADE_REVIEW", "客户降级待复核",
                    "客户 #" + customer.getId() + " 的自动降级申请等待人工复核",
                    "CUSTOMER_TIER_REVIEW", Long.toString(review.id()),
                    "customer-tier-downgrade:" + review.id(),
                    "/customer-tier-reviews?reviewId=" + review.id(),
                    Set.of(NotificationChannel.IN_APP, NotificationChannel.WECOM_APP)));
        }
    }

    private TierLevelDefinition findLevel(List<TierLevelDefinition> levels, String code) {
        if (code == null) {
            return null;
        }
        return levels.stream().filter(level -> level.tierCode().equals(code)).findFirst().orElse(null);
    }

    private String stableKey(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "重算幂等键不能为空");
        }
        return normalized.length() <= 192 ? normalized : "tier-recalc:" + IdempotencyService.sha256(normalized);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
