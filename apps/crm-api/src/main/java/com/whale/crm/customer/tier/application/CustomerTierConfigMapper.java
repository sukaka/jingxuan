package com.whale.crm.customer.tier.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.tier.api.dto.CustomerTierEvaluationResponse;
import com.whale.crm.customer.tier.api.dto.CustomerTierHistoryResponse;
import com.whale.crm.customer.tier.api.dto.CustomerTierReviewResponse;
import com.whale.crm.customer.tier.api.dto.CustomerTierRuleResponse;
import com.whale.crm.customer.tier.domain.CustomerTierDowngradeReview;
import com.whale.crm.customer.tier.domain.CustomerTierHistory;
import com.whale.crm.customer.tier.domain.CustomerTierLevel;
import com.whale.crm.customer.tier.domain.CustomerTierMetricSnapshot;
import com.whale.crm.customer.tier.domain.CustomerTierMetrics;
import com.whale.crm.customer.tier.domain.CustomerTierRepository;
import com.whale.crm.customer.tier.domain.CustomerTierRule;
import com.whale.crm.customer.tier.domain.TierLevelDefinition;
import com.whale.crm.customer.tier.domain.TierMetricDefinition;
import com.whale.crm.customer.tier.domain.TierWindow;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CustomerTierConfigMapper {
    private final CustomerTierRepository repository;
    private final ObjectMapper objectMapper;

    public CustomerTierConfigMapper(CustomerTierRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public List<TierLevelDefinition> levels(CustomerTierRule rule) {
        return repository.findLevels(rule.tenantId(), rule.id()).stream().map(CustomerTierLevel::toDefinition).toList();
    }

    public List<TierMetricDefinition> metrics(CustomerTierRule rule) {
        try {
            return objectMapper.readValue(rule.metricConfigJson(), new TypeReference<>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("分级规则历史配置无法解析", ex);
        }
    }

    public String writeMetrics(List<TierMetricDefinition> metrics) {
        try {
            return objectMapper.writeValueAsString(metrics);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "指标配置无法序列化");
        }
    }

    public CustomerTierRuleResponse toResponse(CustomerTierRule rule) {
        List<TierLevelDefinition> levels = levels(rule);
        List<CustomerTierRuleResponse.Level> levelResponses = levels.stream()
                .map(level -> new CustomerTierRuleResponse.Level(level.tierCode(), level.tierName(),
                        level.sortNo(), level.minScore(), level.minConsumptionFen())).toList();
        List<CustomerTierRuleResponse.Metric> metricResponses = metrics(rule).stream()
                .map(metric -> new CustomerTierRuleResponse.Metric(metric.metricCode(), metric.direction(),
                        metric.weight(), levels.stream().map(level -> new CustomerTierRuleResponse.Threshold(
                                level.tierCode(), metric.thresholds().get(level.tierCode()))).toList())).toList();
        return new CustomerTierRuleResponse(rule.id(), rule.ruleCode(), rule.versionNo(), rule.ruleName(),
                rule.zoneId(), rule.status(), levelResponses, metricResponses, rule.activatedAt(),
                rule.retiredAt(), rule.createdAt(), rule.version());
    }

    public CustomerTierEvaluationResponse toResponse(CustomerTierMetricSnapshot snapshot) {
        CustomerTierMetrics values = snapshot.metrics();
        return new CustomerTierEvaluationResponse(snapshot.id(), snapshot.customerId(), snapshot.ruleId(),
                snapshot.asOf(), snapshot.windowStartInclusive(), snapshot.windowEndExclusive(), snapshot.zoneId(),
                values.paymentAmountFen(), values.paidAmountFen(), values.refundAmountFen(), values.orderCount(),
                values.manualFollowupCount(), values.archivedMessageCount(), values.communicationCount(),
                values.communicationComplete(), snapshot.actualConsumptionFen(), snapshot.score(),
                snapshot.suggestedTierCode(), snapshot.sourceTrigger());
    }

    public CustomerTierEvaluationResponse evaluation(long customerId, CustomerTierRule rule, TierWindow window,
                                                     CustomerTierMetrics values, TierEvaluation evaluation,
                                                     String source) {
        return new CustomerTierEvaluationResponse(null, customerId, rule.id(), window.endExclusive(),
                window.startInclusive(), window.endExclusive(), window.zoneId().getId(), values.paymentAmountFen(),
                values.paidAmountFen(), values.refundAmountFen(), values.orderCount(), values.manualFollowupCount(),
                values.archivedMessageCount(), values.communicationCount(), values.communicationComplete(),
                values.actualConsumptionFen(), evaluation.score(), evaluation.suggestedTierCode(), source);
    }

    public CustomerTierReviewResponse toResponse(CustomerTierDowngradeReview review) {
        return new CustomerTierReviewResponse(review.id(), review.customerId(), review.ruleId(), review.snapshotId(),
                review.fromTierCode(), review.toTierCode(), review.baseCustomerVersion(), review.status(),
                review.requestedBy(), review.reviewedBy(), review.reviewReason(), review.reviewedAt(),
                review.effectiveAt(), review.createdAt(), review.version());
    }

    public CustomerTierHistoryResponse toResponse(CustomerTierHistory history) {
        return new CustomerTierHistoryResponse(history.id(), history.customerId(), history.ruleId(),
                history.snapshotId(), history.reviewId(), history.fromTierCode(), history.toTierCode(),
                history.changeType(), history.actorUserId(), history.reason(), history.effectiveAt(),
                history.createdAt());
    }
}
