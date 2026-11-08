package com.whale.crm.reporting.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.reporting.domain.ReportCriteria;
import com.whale.crm.reporting.domain.ReportQuery;
import com.whale.crm.reporting.domain.ReportScope;
import com.whale.crm.reporting.domain.ReportWindow;
import com.whale.crm.reporting.domain.SkuQualityRules;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ReportQueryFactory {
    static final String METRIC_VERSION = "P2_FACT_V1";
    static final int MAX_PAGE = 10_000;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_DRILL_DOWN = 50;

    private ReportQueryFactory() {
    }

    static ReportQuery create(long tenantId, CurrentUser user, DataScope dataScope,
                              ReportCriteria criteria, int page, int size) {
        if (tenantId <= 0 || user.tenantId() != tenantId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "租户上下文不一致");
        }
        if (criteria == null) {
            throw invalid("报表筛选条件不能为空");
        }
        if (page < 1 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("报表页码或分页大小超过上限");
        }
        if (!criteria.sourceSystem().matches("[A-Z0-9_]{1,48}")) {
            throw invalid("订单来源格式无效");
        }
        if (!Set.of("ALL", "PLATFORM", "INSTITUTION").contains(criteria.receiverType())) {
            throw invalid("收款方类型无效");
        }
        if (!METRIC_VERSION.equals(criteria.metricVersion())) {
            throw invalid("不支持的报表口径版本");
        }
        List<Long> ids = java.util.Arrays.asList(criteria.customerId(), criteria.campaignId(), criteria.campaignBatchId(),
                criteria.channelId(), criteria.staffUserId(), criteria.skuId(), criteria.institutionId());
        if (ids.stream().anyMatch(id -> id != null && id <= 0)) {
            throw invalid("报表筛选 ID 必须为正数");
        }
        ReportWindow window = ReportWindow.of(criteria.from(), criteria.to(), criteria.timeZone());
        ReportScope scope = new ReportScope(dataScope.type().name(), List.copyOf(dataScope.teamIds()), user.userId());
        return new ReportQuery(tenantId, window, scope, criteria, (page - 1) * size, size);
    }

    static String performanceRole(String value) {
        String role = value == null || value.isBlank() ? "MANAGER" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("MANAGER", "BUSINESS").contains(role)) {
            throw invalid("业绩角色只支持 MANAGER 或 BUSINESS");
        }
        return role;
    }

    static DrillDown drillDown(String value, Integer limit, Set<String> allowedTypes) {
        String type = value == null || value.isBlank() ? "NONE" : value.trim().toUpperCase(Locale.ROOT);
        int actualLimit = limit == null ? 20 : limit;
        if (actualLimit < 1 || actualLimit > MAX_DRILL_DOWN) {
            throw invalid("下钻条数必须在 1 到 50 之间");
        }
        if (!"NONE".equals(type) && !allowedTypes.contains(type)) {
            throw invalid("该报表不支持此下钻事实类型");
        }
        return new DrillDown(type, actualLimit);
    }

    static int lowFrequencyThreshold(int value) {
        if (value < 0 || value > SkuQualityRules.MAX_LOW_FREQUENCY_THRESHOLD) {
            throw invalid("低频阈值必须在 0 到 1000 之间");
        }
        return value;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    record DrillDown(String type, int limit) {
        boolean enabled() {
            return !"NONE".equals(type);
        }
    }
}
