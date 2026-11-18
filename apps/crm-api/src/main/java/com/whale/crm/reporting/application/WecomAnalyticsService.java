package com.whale.crm.reporting.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.reporting.api.dto.WecomAnalyticsResponse;
import com.whale.crm.reporting.domain.ReportWindow;
import com.whale.crm.reporting.domain.WecomAnalyticsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class WecomAnalyticsService {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String METRIC_VERSION = "P3_FACT_V1";
    private static final List<String> UNSUPPORTED_METRICS = List.of(
            "MESSAGE_READ_RATE", "H5_OPEN_RATE", "CHAT_CAUSAL_ATTRIBUTION", "QUICK_REPLY_BUSINESS_OUTCOME");

    private final WecomAnalyticsRepository repository;
    private final DataScopeService dataScopeService;

    public WecomAnalyticsService(WecomAnalyticsRepository repository, DataScopeService dataScopeService) {
        this.repository = repository;
        this.dataScopeService = dataScopeService;
    }

    @Transactional(readOnly = true)
    public WecomAnalyticsResponse analytics(CurrentUser user, LocalDate from, LocalDate to, Long employeeId) {
        requireAccess(user, employeeId);
        LocalDate actualTo = to == null ? LocalDate.now(REPORT_ZONE) : to;
        LocalDate actualFrom = from == null ? actualTo.minusDays(29) : from;
        ReportWindow window = ReportWindow.of(actualFrom, actualTo, REPORT_ZONE.getId());
        DataScope scope = dataScopeService.scopeFor(user, DataScopeService.RESOURCE_CUSTOMER);
        if (scope.type() == DataScope.ScopeType.INSTITUTION) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "机构账号不能读取企微客户运营分析");
        }
        if (employeeId != null && scope.type() == DataScope.ScopeType.SELF && employeeId.longValue() != user.userId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能查看其他员工的运营分析");
        }
        if (scope.type() == DataScope.ScopeType.NONE) {
            return empty(window, scope, employeeId);
        }
        var quick = repository.quickReplyFacts(user.tenantId(), window.fromUtc(), window.toUtcExclusive(), scope.type().name(),
                scope.teamIds(), user.userId(), employeeId);
        var suggestions = repository.aiSuggestionFacts(user.tenantId(), window.fromUtc(), window.toUtcExclusive(), scope.type().name(),
                scope.teamIds(), user.userId(), employeeId);
        var aiUsage = repository.aiUsageFacts(user.tenantId(), window.fromUtc(), window.toUtcExclusive(), scope.type().name(),
                scope.teamIds(), user.userId(), employeeId);
        var appointments = repository.appointmentFacts(user.tenantId(), window.fromUtc(), window.toUtcExclusive(), scope.type().name(),
                scope.teamIds(), user.userId(), employeeId);
        var payments = repository.paymentFacts(user.tenantId(), window.fromUtc(), window.toUtcExclusive(), scope.type().name(),
                scope.teamIds(), user.userId(), employeeId);
        long sdkResults = quick.sdkSuccessCount() + quick.sdkFailedCount() + quick.sdkUnknownCount();
        return new WecomAnalyticsResponse(window.from(), window.to(), window.fromUtc(), window.toUtcExclusive(),
                window.timeZone(), METRIC_VERSION, scope.type().name(), employeeId, Instant.now(),
                new WecomAnalyticsResponse.QuickReplyMetrics(quick.usageCount(), quick.copiedCount(),
                        quick.sdkSuccessCount(), quick.sdkFailedCount(), quick.sdkUnknownCount(), quick.editedCount(),
                        quick.aiLinkedUsageCount(), percentage(quick.sdkSuccessCount(), sdkResults)),
                new WecomAnalyticsResponse.AiReplyMetrics(suggestions.generatedCount(), suggestions.acceptedCount(),
                        suggestions.rejectedCount(), suggestions.pendingCount(), suggestions.editedAcceptedCount(),
                        suggestions.mockGeneratedCount(), aiUsage.copiedCount(), aiUsage.sdkSuccessCount(),
                        aiUsage.sdkFailedCount(), aiUsage.sdkUnknownCount(),
                        percentage(suggestions.acceptedCount(), suggestions.generatedCount())),
                new WecomAnalyticsResponse.ConversionMetrics(appointments.confirmedCount(), appointments.checkedInCount(),
                        payments.paidOrderCount(), payments.paidCustomerCount(), payments.repeatCustomerCount(),
                        percentage(appointments.checkedInCount(), appointments.confirmedCount()),
                        percentage(payments.repeatCustomerCount(), payments.paidCustomerCount())),
                UNSUPPORTED_METRICS);
    }

    private void requireAccess(CurrentUser user, Long employeeId) {
        if (!user.permissions().contains("report:read")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有运营分析读取权限");
        }
        if (employeeId != null && employeeId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "员工筛选 ID 必须为正数");
        }
    }

    private WecomAnalyticsResponse empty(ReportWindow window, DataScope scope, Long employeeId) {
        return new WecomAnalyticsResponse(window.from(), window.to(), window.fromUtc(), window.toUtcExclusive(),
                window.timeZone(), METRIC_VERSION, scope.type().name(), employeeId, Instant.now(),
                new WecomAnalyticsResponse.QuickReplyMetrics(0, 0, 0, 0, 0, 0, 0, null),
                new WecomAnalyticsResponse.AiReplyMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null),
                new WecomAnalyticsResponse.ConversionMetrics(0, 0, 0, 0, 0, null, null), UNSUPPORTED_METRICS);
    }

    private BigDecimal percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
}
