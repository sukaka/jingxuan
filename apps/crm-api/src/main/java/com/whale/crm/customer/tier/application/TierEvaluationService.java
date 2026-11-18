package com.whale.crm.customer.tier.application;

import com.whale.crm.customer.tier.domain.CustomerTierMetrics;
import com.whale.crm.customer.tier.domain.TierLevelDefinition;
import com.whale.crm.customer.tier.domain.TierMetricDefinition;
import com.whale.crm.customer.tier.domain.TierWindow;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TierEvaluationService {
    private static final Set<String> REQUIRED_TIERS = Set.of("S", "A", "B", "C", "D");

    public TierWindow window(Instant asOf, ZoneId zoneId) {
        if (asOf == null || zoneId == null) {
            throw validation("重算时间与规则时区不能为空");
        }
        return new TierWindow(asOf.atZone(zoneId).minusMonths(12).toInstant(), asOf, zoneId);
    }

    public void validate(List<TierLevelDefinition> levels, List<TierMetricDefinition> metrics) {
        List<TierLevelDefinition> ordered = validateLevels(levels);
        if (metrics != null && !metrics.isEmpty()) {
            throw validation("客户分级仅按实际消费金额计算，不需要指标、权重或得分");
        }
        validateConsumptionLevels(ordered);
    }

    public TierEvaluation evaluate(List<TierLevelDefinition> levels, List<TierMetricDefinition> metrics,
                                   CustomerTierMetrics values) {
        if (values == null) {
            throw validation("消费快照不能为空");
        }
        List<TierLevelDefinition> ordered = validateLevels(levels);
        if (ordered.stream().anyMatch(level -> level.minConsumptionFen() == null)) {
            return new TierEvaluation(null, null, false);
        }
        validateConsumptionLevels(ordered);
        long actualConsumption = values.actualConsumptionFen();
        String target = null;
        for (TierLevelDefinition level : ordered) {
            if (actualConsumption >= level.minConsumptionFen()) {
                target = level.tierCode();
                break;
            }
        }
        return new TierEvaluation(null, target, true);
    }

    private void validateConsumptionLevels(List<TierLevelDefinition> ordered) {
        Long previous = null;
        for (TierLevelDefinition level : ordered) {
            Long threshold = level.minConsumptionFen();
            if (threshold == null || threshold < 0) {
                throw validation("各等级最低实际消费金额不能为空且必须为非负整数分");
            }
            if (previous != null && previous < threshold) {
                throw validation("高等级最低实际消费金额不能低于低等级");
            }
            previous = threshold;
        }
        if (ordered.getLast().minConsumptionFen() != 0L) {
            throw validation("最低等级最低实际消费金额必须为 0");
        }
    }

    private List<TierLevelDefinition> validateLevels(List<TierLevelDefinition> levels) {
        if (levels == null || levels.size() != REQUIRED_TIERS.size()) {
            throw validation("等级项必须且只能包含 S/A/B/C/D");
        }
        Set<String> codes = new HashSet<>();
        Set<Integer> sorts = new HashSet<>();
        for (TierLevelDefinition level : levels) {
            if (level == null || level.tierCode() == null || !REQUIRED_TIERS.contains(level.tierCode())
                    || !codes.add(level.tierCode()) || level.tierName() == null || level.tierName().isBlank()
                    || level.tierName().strip().length() > 64 || level.sortNo() < 1 || level.sortNo() > 5
                    || !sorts.add(level.sortNo())) {
                throw validation("等级编码、名称或顺序配置无效");
            }
        }
        return levels.stream().sorted(Comparator.comparingInt(TierLevelDefinition::sortNo)).toList();
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
