package com.whale.crm.reporting.application;

import com.whale.crm.customer.application.TaskApplicationService;
import com.whale.crm.customer.domain.TaskDutySummary;
import com.whale.crm.fulfillment.application.AppointmentApplicationService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.refund.application.RefundApplicationService;
import com.whale.crm.reporting.api.dto.CampaignReportResponse;
import com.whale.crm.reporting.api.dto.OperationsDashboardResponse;
import com.whale.crm.reporting.api.dto.OperationsDutyResponse;
import com.whale.crm.reporting.api.dto.OperationsReportResponse;
import com.whale.crm.reporting.api.dto.PendingRatio;
import com.whale.crm.reporting.api.dto.PerformanceReportResponse;
import com.whale.crm.reporting.api.dto.SkuQualityReportResponse;
import com.whale.crm.reporting.domain.ReportCriteria;
import com.whale.crm.reporting.domain.ReportFact;
import com.whale.crm.reporting.domain.ReportQuery;
import com.whale.crm.reporting.domain.ReportingRepository;
import com.whale.crm.reporting.domain.SkuQualityAssessment;
import com.whale.crm.reporting.domain.SkuQualityFacts;
import com.whale.crm.reporting.domain.SkuQualityRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class ReportingApplicationService {
    private static final Set<String> OPERATIONS_DRILL_DOWN = Set.of(
            "FOLLOWUP", "APPOINTMENT_CONFIRMED", "CHECKED_IN", "SERVICE_DONE", "REDEMPTION", "PAYMENT", "REFUND");
    private static final Set<String> PERFORMANCE_DRILL_DOWN = Set.of("PAYMENT", "REFUND");
    private static final Set<String> CAMPAIGN_DRILL_DOWN = Set.of(
            "APPOINTMENT_CONFIRMED", "CHECKED_IN", "REDEMPTION", "PAYMENT", "REFUND");
    private static final Set<String> SKU_DRILL_DOWN = Set.of("PAID_ORDER_LINE");

    private final ReportingRepository repository;
    private final DataScopeService dataScopeService;
    private final TaskApplicationService taskService;
    private final AppointmentApplicationService appointmentService;
    private final RefundApplicationService refundService;

    public ReportingApplicationService(ReportingRepository repository, DataScopeService dataScopeService,
                                       TaskApplicationService taskService,
                                       AppointmentApplicationService appointmentService,
                                       RefundApplicationService refundService) {
        this.repository = repository;
        this.dataScopeService = dataScopeService;
        this.taskService = taskService;
        this.appointmentService = appointmentService;
        this.refundService = refundService;
    }

    @Transactional(readOnly = true)
    public OperationsDashboardResponse operationsDashboard(long tenantId, CurrentUser user, ReportCriteria criteria) {
        OperationsReportResponse operationsReport = operations(tenantId, user, criteria, "NONE", 1);
        PerformanceReportResponse performanceReport = performance(tenantId, user, criteria, "MANAGER", 1, 1, "NONE", 1);
        CampaignReportResponse campaignReport = campaigns(tenantId, user, criteria, 1, 6, "NONE", 1);
        Instant sourceWatermark = watermark(java.util.stream.Stream.of(operationsReport.sourceWatermark(),
                performanceReport.sourceWatermark(), campaignReport.sourceWatermark()).toList());
        return new OperationsDashboardResponse(operationsReport.from(), operationsReport.to(), operationsReport.fromUtc(),
                operationsReport.toUtcExclusive(), operationsReport.timeZone(), operationsReport.sourceSystem(),
                operationsReport.receiverType(), operationsReport.metricVersion(), sourceWatermark,
                operationsReport.metrics(), performanceReport.dailyItems(), operationsReport.institutionRows(),
                campaignReport.items(), duty(tenantId, user, criteria));
    }

    private OperationsDutyResponse duty(long tenantId, CurrentUser user, ReportCriteria criteria) {
        ReportQuery query = query(tenantId, user, criteria, 1, 1);
        Long newCustomers = !hasPermission(user, "customer:read") ? null : query.scope().empty()
                ? 0L : repository.countNewCustomers(query);
        boolean canReadTasks = hasPermission(user, "task:read");
        boolean canReadAppointments = hasPermission(user, "appointment:read");
        boolean canReadRefunds = hasPermission(user, "refund:read");
        if (query.scope().empty()) {
            return new OperationsDutyResponse(newCustomers, canReadTasks ? 0L : null, canReadTasks ? 0L : null,
                    canReadTasks ? 0L : null, canReadAppointments ? 0L : null,
                    canReadAppointments ? 0L : null, canReadRefunds ? 0L : null);
        }
        Instant now = Instant.now();
        ZoneId zone = ZoneId.of(criteria.timeZone());
        Instant dayEndExclusive = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
        TaskDutySummary tasks = canReadTasks ? taskService.dutySummary(tenantId, user, now, dayEndExclusive) : null;
        Long pendingAppointments = canReadAppointments ? appointmentService.search(tenantId, user,
                criteria.customerId(), criteria.institutionId(), "PENDING_INSTITUTION", 1, 1).total() : null;
        Long awaitingRedemption = canReadAppointments ? appointmentService.search(tenantId, user,
                criteria.customerId(), criteria.institutionId(), "SERVICE_DONE", 1, 1).total() : null;
        Long pendingRefunds = canReadRefunds ? refundService.search(tenantId, user, null,
                criteria.customerId(), "REQUESTED", 1, 1).total() : null;
        return new OperationsDutyResponse(newCustomers, tasks == null ? null : tasks.pendingTaskCount(),
                tasks == null ? null : tasks.dueFollowupCount(), tasks == null ? null : tasks.overdueTaskCount(),
                pendingAppointments, awaitingRedemption, pendingRefunds);
    }

    private boolean hasPermission(CurrentUser user, String permission) {
        return user.permissions().contains(permission);
    }

    @Transactional(readOnly = true)
    public OperationsReportResponse operations(long tenantId, CurrentUser user, ReportCriteria criteria,
                                               String drillDownType, Integer drillDownLimit) {
        ReportQuery query = query(tenantId, user, criteria, 1, 1);
        ReportQueryFactory.DrillDown drillDown = ReportQueryFactory.drillDown(
                drillDownType, drillDownLimit, OPERATIONS_DRILL_DOWN);
        ReportingRepository.OperationsFactRow facts = query.scope().empty()
                ? ReportingRepository.OperationsFactRow.empty() : repository.operations(query);
        List<ReportingRepository.OperationsStaffFactRow> staffFacts = query.scope().empty()
                ? List.of() : repository.operationsStaff(query);
        List<ReportingRepository.OperationsInstitutionFactRow> institutionFacts = query.scope().empty()
                ? List.of() : repository.operationsInstitutions(query);
        List<ReportFact> drillDownItems = query.scope().empty() || !drillDown.enabled()
                ? List.of() : repository.operationsDrillDown(query, drillDown.type(), drillDown.limit());
        OperationsReportResponse.OperationsMetrics metrics = new OperationsReportResponse.OperationsMetrics(
                facts.contactCustomerCount(), facts.contactEventCount(), facts.paidOrderCount(),
                facts.confirmedAppointmentCount(), facts.checkedInCustomerCount(), facts.checkedInAppointmentCount(),
                facts.serviceCompletedCount(), facts.redemptionBatchCount(), facts.redeemedCustomerCount(),
                facts.redeemedCount(), facts.refundCount(), facts.paidFen(), facts.platformPaidFen(),
                facts.institutionPaidFen(), facts.refundFen(), facts.platformRefundFen(), facts.institutionRefundFen(),
                subtract(facts.paidFen(), facts.refundFen()), subtract(facts.platformPaidFen(), facts.platformRefundFen()),
                subtract(facts.institutionPaidFen(), facts.institutionRefundFen()));
        List<OperationsReportResponse.StaffRow> staffRows = staffFacts.stream()
                .map(fact -> new OperationsReportResponse.StaffRow(fact.staffUserId(), fact.staffName(),
                        fact.contactCustomerCount(), fact.contactEventCount(), fact.paidOrderCount(),
                        fact.confirmedAppointmentCount(), fact.serviceCompletedCount(), fact.checkedInCustomerCount(),
                        fact.checkedInAppointmentCount(), fact.redemptionBatchCount(), fact.redeemedCustomerCount(),
                        fact.redeemedCount(), fact.paidFen(), operationsConversionRates()))
                .toList();
        List<OperationsReportResponse.InstitutionRow> institutionRows = institutionFacts.stream()
                .map(fact -> new OperationsReportResponse.InstitutionRow(fact.institutionId(), fact.institutionName(),
                        fact.confirmedAppointmentCount(), fact.serviceCompletedCount(), fact.checkedInCustomerCount(),
                        fact.checkedInAppointmentCount(), fact.redemptionBatchCount(), fact.redeemedCustomerCount(),
                        fact.redeemedCount(), fact.pendingReconciliationFen(),
                        fact.pendingReconciliationFen() == null ? "PENDING_VERIFICATION" : "VERIFIED",
                        operationsConversionRates()))
                .toList();
        return new OperationsReportResponse(query.window().from(), query.window().to(), query.window().fromUtc(),
                query.window().toUtcExclusive(), query.window().timeZone(), query.criteria().sourceSystem(),
                query.criteria().receiverType(), query.criteria().metricVersion(), facts.sourceWatermark(), metrics,
                operationsConversionRates(), staffRows, institutionRows, drillDownItems,
                drillDown.type(), drillDown.limit());
    }

    @Transactional(readOnly = true)
    public PerformanceReportResponse performance(long tenantId, CurrentUser user, ReportCriteria criteria,
                                                 String performanceRole, int page, int size,
                                                 String drillDownType, Integer drillDownLimit) {
        String role = ReportQueryFactory.performanceRole(performanceRole);
        ReportQuery query = query(tenantId, user, criteria, page, size);
        ReportQueryFactory.DrillDown drillDown = ReportQueryFactory.drillDown(
                drillDownType, drillDownLimit, PERFORMANCE_DRILL_DOWN);
        List<ReportingRepository.PerformanceFactRow> facts = query.scope().empty()
                ? List.of() : repository.performance(query, role);
        List<PerformanceReportResponse.PerformanceRow> rows = facts.stream().map(this::performanceRow)
                .sorted(Comparator.comparingLong(PerformanceReportResponse.PerformanceRow::netPaidFen).reversed()
                        .thenComparing(PerformanceReportResponse.PerformanceRow::userId,
                                Comparator.nullsLast(Long::compareTo)))
                .toList();
        PerformanceReportResponse.PerformanceTotals totals = performanceTotals(facts);
        List<PerformanceReportResponse.DailyItem> dailyItems = query.scope().empty()
                ? List.of() : dailyItems(query, role);
        TeamTarget teamTarget = teamTarget(query, role, facts, totals);
        List<ReportFact> drillDownItems = query.scope().empty() || !drillDown.enabled()
                ? List.of() : repository.performanceDrillDown(query, role, drillDown.type(), drillDown.limit());
        return new PerformanceReportResponse(query.window().from(), query.window().to(), query.window().fromUtc(),
                query.window().toUtcExclusive(), query.window().timeZone(), query.criteria().sourceSystem(),
                query.criteria().receiverType(), query.criteria().metricVersion(), watermark(facts.stream()
                .map(ReportingRepository.PerformanceFactRow::sourceWatermark).toList()), role, totals,
                page(rows, query.offset(), query.size()), dailyItems, facts.size(), page, size,
                "UNCONFIGURED", "UNCONFIGURED", "NOT_PAYROLL", teamTarget.amountFen(), teamTarget.status(),
                teamTarget.completionRate(), teamTarget.completionStatus(), drillDownItems,
                drillDown.type(), drillDown.limit());
    }

    @Transactional(readOnly = true)
    public CampaignReportResponse campaigns(long tenantId, CurrentUser user, ReportCriteria criteria,
                                            int page, int size, String drillDownType, Integer drillDownLimit) {
        ReportQuery query = query(tenantId, user, criteria, page, size);
        ReportQueryFactory.DrillDown drillDown = ReportQueryFactory.drillDown(
                drillDownType, drillDownLimit, CAMPAIGN_DRILL_DOWN);
        List<ReportingRepository.CampaignFactRow> facts = query.scope().empty()
                ? List.of() : repository.campaigns(query);
        ReportingRepository.CampaignFactRow totalFacts = query.scope().empty()
                ? emptyCampaignFacts() : repository.campaignTotals(query);
        List<CampaignReportResponse.CampaignRow> rows = facts.stream().map(this::campaignRow)
                .sorted(Comparator.comparing(CampaignReportResponse.CampaignRow::campaignId)
                        .thenComparing(CampaignReportResponse.CampaignRow::campaignBatchId,
                                Comparator.nullsFirst(Long::compareTo)))
                .toList();
        CampaignReportResponse.CampaignTotals totals = campaignTotals(totalFacts);
        List<ReportFact> drillDownItems = query.scope().empty() || !drillDown.enabled()
                ? List.of() : repository.campaignDrillDown(query, drillDown.type(), drillDown.limit());
        return new CampaignReportResponse(query.window().from(), query.window().to(), query.window().fromUtc(),
                query.window().toUtcExclusive(), query.window().timeZone(), query.criteria().sourceSystem(),
                query.criteria().receiverType(), query.criteria().metricVersion(), totalFacts.sourceWatermark(), totals,
                page(rows, query.offset(), query.size()), facts.size(), page, size, drillDownItems,
                drillDown.type(), drillDown.limit());
    }

    @Transactional(readOnly = true)
    public SkuQualityReportResponse skuQuality(long tenantId, CurrentUser user, ReportCriteria criteria,
                                               int page, int size, int lowFrequencyThreshold,
                                               String drillDownType, Integer drillDownLimit) {
        int threshold = ReportQueryFactory.lowFrequencyThreshold(lowFrequencyThreshold);
        ReportQuery query = query(tenantId, user, criteria, page, size);
        ReportQueryFactory.DrillDown drillDown = ReportQueryFactory.drillDown(
                drillDownType, drillDownLimit, SKU_DRILL_DOWN);
        List<ReportingRepository.SkuQualityFactRow> facts = query.scope().empty()
                ? List.of() : repository.skuQuality(query);
        long total = query.scope().empty() ? 0 : repository.countSku(query);
        Instant sourceWatermark = query.scope().empty() ? null : repository.skuSourceWatermark(query);
        List<SkuQualityReportResponse.SkuQualityRow> rows = facts.stream()
                .map(fact -> skuQualityRow(fact, threshold)).toList();
        List<ReportFact> drillDownItems = query.scope().empty() || !drillDown.enabled()
                ? List.of() : repository.skuDrillDown(query, drillDown.type(), drillDown.limit());
        return new SkuQualityReportResponse(query.window().from(), query.window().to(), query.window().fromUtc(),
                query.window().toUtcExclusive(), query.window().timeZone(), query.criteria().sourceSystem(),
                query.criteria().receiverType(), query.criteria().metricVersion(), sourceWatermark, threshold,
                query.window().periodDays(), "MANUAL_REVIEW_ONLY", qualityRules(threshold), rows, total, page, size,
                drillDownItems, drillDown.type(), drillDown.limit());
    }

    private ReportQuery query(long tenantId, CurrentUser user, ReportCriteria criteria, int page, int size) {
        DataScope scope = dataScopeService.scopeFor(user, DataScopeService.RESOURCE_CUSTOMER);
        return ReportQueryFactory.create(tenantId, user, scope, criteria, page, size);
    }

    private PerformanceReportResponse.PerformanceRow performanceRow(ReportingRepository.PerformanceFactRow fact) {
        String displayName = fact.userId() == null ? "未配置" : fact.displayName();
        String attributionStatus = fact.userId() == null ? "UNCONFIGURED"
                : fact.displayName() == null ? "USER_NOT_FOUND" : "AT_SALE_SNAPSHOT";
        return new PerformanceReportResponse.PerformanceRow(fact.userId(), displayName, attributionStatus,
                fact.paidOrderCount(), fact.refundCount(), fact.paidFen(), fact.platformPaidFen(),
                fact.institutionPaidFen(), fact.refundFen(), fact.platformRefundFen(), fact.institutionRefundFen(),
                subtract(fact.paidFen(), fact.refundFen()),
                subtract(fact.platformPaidFen(), fact.platformRefundFen()),
                subtract(fact.institutionPaidFen(), fact.institutionRefundFen()), null, "UNCONFIGURED",
                null, "UNCONFIGURED", null, "UNCONFIGURED", fact.sourceWatermark());
    }

    private PerformanceReportResponse.PerformanceTotals performanceTotals(
            List<ReportingRepository.PerformanceFactRow> facts) {
        long paidOrders = 0;
        long refunds = 0;
        long paid = 0;
        long platformPaid = 0;
        long institutionPaid = 0;
        long refunded = 0;
        long platformRefunded = 0;
        long institutionRefunded = 0;
        for (ReportingRepository.PerformanceFactRow fact : facts) {
            paidOrders = add(paidOrders, fact.paidOrderCount());
            refunds = add(refunds, fact.refundCount());
            paid = add(paid, fact.paidFen());
            platformPaid = add(platformPaid, fact.platformPaidFen());
            institutionPaid = add(institutionPaid, fact.institutionPaidFen());
            refunded = add(refunded, fact.refundFen());
            platformRefunded = add(platformRefunded, fact.platformRefundFen());
            institutionRefunded = add(institutionRefunded, fact.institutionRefundFen());
        }
        return new PerformanceReportResponse.PerformanceTotals(paidOrders, refunds, paid, platformPaid, institutionPaid,
                refunded, platformRefunded, institutionRefunded, subtract(paid, refunded),
                subtract(platformPaid, platformRefunded), subtract(institutionPaid, institutionRefunded));
    }

    private List<PerformanceReportResponse.DailyItem> dailyItems(ReportQuery query, String role) {
        ZoneId zoneId = ZoneId.of(query.window().timeZone());
        Map<LocalDate, DailyAccumulator> byDate = new TreeMap<>();
        for (ReportingRepository.DailyFactRow fact : repository.dailyFacts(query, role)) {
            LocalDate statDate = fact.eventAt().atZone(zoneId).toLocalDate();
            byDate.computeIfAbsent(statDate, ignored -> new DailyAccumulator()).add(fact);
        }
        return byDate.entrySet().stream().map(entry -> entry.getValue().toResponse(entry.getKey())).toList();
    }

    private TeamTarget teamTarget(ReportQuery query, String role, List<ReportingRepository.PerformanceFactRow> facts,
                                  PerformanceReportResponse.PerformanceTotals totals) {
        YearMonth period = YearMonth.from(query.window().from());
        boolean singleMonth = period.equals(YearMonth.from(query.window().to()))
                && query.window().periodDays() == query.window().from().lengthOfMonth();
        if (!singleMonth || facts.isEmpty() || facts.stream().anyMatch(fact -> fact.userId() == null)) {
            return TeamTarget.unconfigured();
        }
        List<Long> staffIds = facts.stream().map(ReportingRepository.PerformanceFactRow::userId)
                .distinct().sorted().toList();
        List<ReportingRepository.ApprovedTargetRow> approved = repository.approvedTargets(
                query, role, period.toString(), staffIds);
        if (approved.size() != staffIds.size()) {
            return TeamTarget.unconfigured();
        }
        Map<Long, Long> byStaff = new HashMap<>();
        approved.forEach(target -> byStaff.put(target.staffUserId(), target.targetFen()));
        if (!byStaff.keySet().equals(new HashSet<>(staffIds))) {
            return TeamTarget.unconfigured();
        }
        long targetFen = 0;
        for (Long staffId : staffIds) {
            targetFen = add(targetFen, byStaff.get(staffId));
        }
        if (targetFen == 0) {
            return new TeamTarget(0L, "APPROVED", null, "ZERO_TARGET");
        }
        BigDecimal completionRate = BigDecimal.valueOf(totals.netPaidFen())
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(targetFen), 4, RoundingMode.HALF_UP);
        return new TeamTarget(targetFen, "APPROVED", completionRate, "CALCULATED");
    }

    private static final class DailyAccumulator {
        private final Set<Long> orderIds = new HashSet<>();
        private final Set<Long> refundIds = new HashSet<>();
        private long paidFen;
        private long refundFen;

        void add(ReportingRepository.DailyFactRow fact) {
            if ("PAYMENT".equals(fact.factType())) {
                orderIds.add(fact.parentId());
                paidFen = Math.addExact(paidFen, fact.amountFen());
            } else if ("REFUND".equals(fact.factType())) {
                refundIds.add(fact.parentId());
                refundFen = Math.addExact(refundFen, fact.amountFen());
            }
        }

        PerformanceReportResponse.DailyItem toResponse(LocalDate statDate) {
            return new PerformanceReportResponse.DailyItem(statDate, orderIds.size(), refundIds.size(),
                    paidFen, refundFen, Math.subtractExact(paidFen, refundFen));
        }
    }

    private record TeamTarget(Long amountFen, String status, BigDecimal completionRate, String completionStatus) {
        static TeamTarget unconfigured() {
            return new TeamTarget(null, "UNCONFIGURED", null, "UNCONFIGURED");
        }
    }

    private CampaignReportResponse.CampaignRow campaignRow(ReportingRepository.CampaignFactRow fact) {
        return new CampaignReportResponse.CampaignRow(fact.campaignId(), fact.campaignBatchId(), fact.campaignCode(),
                fact.campaignBatchCode(), fact.campaignName(), fact.campaignBatchName(), fact.paidOrderCount(),
                fact.refundCount(), fact.confirmedAppointmentCount(), fact.checkedInCustomerCount(),
                fact.checkedInAppointmentCount(), fact.redemptionBatchCount(), fact.redeemedCount(), fact.paidFen(),
                fact.platformPaidFen(), fact.institutionPaidFen(), fact.refundFen(), fact.platformRefundFen(),
                fact.institutionRefundFen(), subtract(fact.paidFen(), fact.refundFen()),
                subtract(fact.platformPaidFen(), fact.platformRefundFen()),
                subtract(fact.institutionPaidFen(), fact.institutionRefundFen()), null, "SOURCE_NOT_AVAILABLE",
                campaignConversionRates(), fact.sourceWatermark());
    }

    private CampaignReportResponse.CampaignTotals campaignTotals(ReportingRepository.CampaignFactRow fact) {
        return new CampaignReportResponse.CampaignTotals(fact.paidOrderCount(), fact.refundCount(),
                fact.confirmedAppointmentCount(), fact.checkedInCustomerCount(), fact.checkedInAppointmentCount(),
                fact.redemptionBatchCount(), fact.redeemedCount(), fact.paidFen(), fact.platformPaidFen(),
                fact.institutionPaidFen(), fact.refundFen(), fact.platformRefundFen(), fact.institutionRefundFen(),
                subtract(fact.paidFen(), fact.refundFen()),
                subtract(fact.platformPaidFen(), fact.platformRefundFen()),
                subtract(fact.institutionPaidFen(), fact.institutionRefundFen()), null, "SOURCE_NOT_AVAILABLE");
    }

    private SkuQualityReportResponse.SkuQualityRow skuQualityRow(ReportingRepository.SkuQualityFactRow fact,
                                                                 int threshold) {
        SkuQualityAssessment assessment = SkuQualityRules.evaluate(new SkuQualityFacts(fact.skuCode(),
                fact.packageName(), fact.specName(), fact.activeInstitutionCount(), fact.salesQuantity()), threshold);
        String standardName = fact.packageName() == null || fact.packageName().isBlank()
                || fact.specName() == null || fact.specName().isBlank()
                ? null : fact.packageName() + "｜" + fact.specName();
        return new SkuQualityReportResponse.SkuQualityRow(fact.skuId(), fact.skuCode(), fact.packageVersionId(),
                fact.packageName(), fact.specName(), standardName, fact.salePriceFen(), fact.skuStatus(),
                fact.activeInstitutionCount(), fact.salesQuantity(), fact.paidOrderCount(), fact.paidLineCount(),
                fact.lastSoldAt(), assessment.lowFrequency(), assessment.qualityStatus(), assessment.issueCodes(),
                assessment.automaticAction());
    }

    private List<SkuQualityReportResponse.QualityRule> qualityRules(int threshold) {
        return List.of(
                new SkuQualityReportResponse.QualityRule("MISSING_SKU_CODE", "SKU 编码为空或仅包含空白字符"),
                new SkuQualityReportResponse.QualityRule("MISSING_STANDARD_NAME", "套餐标准名或 SKU 规格名为空"),
                new SkuQualityReportResponse.QualityRule("MISSING_ACTIVE_INSTITUTION", "未配置 ACTIVE 状态的适用机构"),
                new SkuQualityReportResponse.QualityRule("LOW_FREQUENCY",
                        "统计期限内实际已支付订单行销量小于等于 " + threshold + "；仅提示人工复核，不自动停售或下架"));
    }

    private Map<String, PendingRatio> operationsConversionRates() {
        Map<String, PendingRatio> rates = new LinkedHashMap<>();
        rates.put("contactToPaid", PendingRatio.q13("触达到成交转化率的分母与去重规则待确认"));
        rates.put("paidToCheckedIn", PendingRatio.q13("成交到到院转化率的分母与时间窗口待确认"));
        rates.put("appointmentToCheckedIn", PendingRatio.q13("预约到到院转化率的分母与时间窗口待确认"));
        rates.put("appointmentToServiceCompleted", PendingRatio.q13("预约到服务完成转化率的分母与统计范围待确认"));
        rates.put("checkedInToRedeemed", PendingRatio.q13("到院到核销转化率的分母与次数口径待确认"));
        return Map.copyOf(rates);
    }

    private Map<String, PendingRatio> campaignConversionRates() {
        Map<String, PendingRatio> rates = new LinkedHashMap<>();
        rates.put("contactToPaid", PendingRatio.q13("活动触达到成交转化率待确认，且活动触达事实源尚未建立"));
        rates.put("appointmentToCheckedIn", PendingRatio.q13("活动预约到到院转化率分母待确认"));
        rates.put("checkedInToRedeemed", PendingRatio.q13("活动到院到核销转化率分母待确认"));
        return Map.copyOf(rates);
    }

    private ReportingRepository.CampaignFactRow emptyCampaignFacts() {
        return new ReportingRepository.CampaignFactRow(null, null, null, null, null, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }

    private Instant watermark(List<Instant> values) {
        return values.stream().filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
    }

    private <T> List<T> page(List<T> rows, int offset, int size) {
        if (offset >= rows.size()) {
            return List.of();
        }
        return List.copyOf(rows.subList(offset, Math.min(rows.size(), offset + size)));
    }

    private long add(long left, long right) {
        return Math.addExact(left, right);
    }

    private long subtract(long left, long right) {
        return Math.subtractExact(left, right);
    }
}
