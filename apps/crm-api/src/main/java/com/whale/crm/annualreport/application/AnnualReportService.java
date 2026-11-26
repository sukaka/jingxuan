package com.whale.crm.annualreport.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.annualreport.api.dto.AnnualReportBatchGenerateRequest;
import com.whale.crm.annualreport.api.dto.AnnualReportBatchGenerateResponse;
import com.whale.crm.annualreport.api.dto.AnnualReportDisableRequest;
import com.whale.crm.annualreport.api.dto.AnnualReportItemResponse;
import com.whale.crm.annualreport.api.dto.AnnualReportSessionResponse;
import com.whale.crm.annualreport.api.dto.AnnualReportSummaryResponse;
import com.whale.crm.annualreport.api.dto.CreateAnnualReportRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualReportResponse;
import com.whale.crm.annualreport.domain.AnnualReport;
import com.whale.crm.annualreport.domain.AnnualReportCustomerFact;
import com.whale.crm.annualreport.domain.AnnualReportRepository;
import com.whale.crm.annualreport.domain.AnnualReportVisitSession;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 客户年终购买轨迹 H5 管理与真实数据聚合服务。
 * 严格执行租户隔离、ALL/TEAM/SELF 数据范围、幂等键、乐观锁与审计日志。
 */
@Service
public class AnnualReportService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter MD_FMT = DateTimeFormatter.ofPattern("MM.dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String CREATE_SCOPE = "annual.report.create";
    private static final String BATCH_SCOPE = "annual.report.batch";
    private static final String DISABLE_SCOPE = "annual.report.disable";
    private static final String[] SCENE_LABELS = {
            "第1幕 · 启幕相识",
            "第2幕 · 四季轨迹",
            "第3幕 · 深夜光影",
            "第4幕 · 年度挚爱",
            "第5幕 · 权益宝库",
            "第6幕 · 纪念灯箱"
    };

    private final AnnualReportRepository repository;
    private final CustomerScopeSupport scopeSupport;
    private final AnnualReportCopyEngine copyEngine;
    private final QuickReplyTextMasker masker;
    private final WecomSidebarProperties sidebarProperties;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public AnnualReportService(AnnualReportRepository repository,
                               CustomerScopeSupport scopeSupport,
                               AnnualReportCopyEngine copyEngine,
                               QuickReplyTextMasker masker,
                               WecomSidebarProperties sidebarProperties,
                               IdempotencyService idempotencyService,
                               AuditService auditService,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.scopeSupport = scopeSupport;
        this.copyEngine = copyEngine;
        this.masker = masker;
        this.sidebarProperties = sidebarProperties;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AnnualReportItemResponse create(CurrentUser user, CreateAnnualReportRequest request, String idempotencyKey) {
        String payloadHash = requestPayload(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), CREATE_SCOPE, idempotencyKey, payloadHash);
        if (replay.isPresent()) {
            long reportId = parseResourceId(replay.orElseThrow());
            AnnualReport existing = requireVisibleReport(user, reportId);
            return toItemResponse(existing);
        }

        Customer customer = scopeSupport.requireVisible(user.tenantId(), request.customerId(), user);
        AnnualReport created = generateForCustomer(user, customer, request.reportYear(),
                request.toneCode(), request.genderHint(), request.customButlerNote(),
                Boolean.TRUE.equals(request.disablePreviousActive()));

        auditService.record(user.tenantId(), user.userId(), "ANNUAL_REPORT_CREATE", "ANNUAL_REPORT",
                Long.toString(created.id()), null,
                Map.of("customerId", created.customerId(), "reportYear", created.reportYear(),
                        "toneCode", created.toneCode(), "orderCount", created.orderCount(),
                        "netSpendFen", created.netSpendFen()),
                "生成客户年度购买轨迹 H5 只读快照");
        idempotencyService.recordSuccess(user.tenantId(), CREATE_SCOPE, idempotencyKey, "ANNUAL_REPORT",
                Long.toString(created.id()), payloadHash);
        return toItemResponse(created);
    }

    @Transactional
    public AnnualReportBatchGenerateResponse batchGenerate(CurrentUser user,
                                                           AnnualReportBatchGenerateRequest request,
                                                           String idempotencyKey) {
        String payloadHash = requestPayload(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), BATCH_SCOPE, idempotencyKey, payloadHash);
        if (replay.isPresent()) {
            return readBatchReplay(user.tenantId(), replay.orElseThrow().getResourceId(), request.reportYear());
        }

        List<Long> uniqueIds = new ArrayList<>(new LinkedHashSet<>(request.customerIds()));
        List<AnnualReportItemResponse> createdItems = new ArrayList<>();
        List<AnnualReportBatchGenerateResponse.SkippedCustomer> skipped = new ArrayList<>();
        boolean disablePrev = Boolean.TRUE.equals(request.disablePreviousActive());

        for (Long customerId : uniqueIds) {
            try {
                Customer customer = scopeSupport.requireVisible(user.tenantId(), customerId, user);
                AnnualReport created = generateForCustomer(user, customer, request.reportYear(),
                        request.toneCode(), "UNKNOWN", null, disablePrev);
                createdItems.add(toItemResponse(created));
            } catch (BusinessException ex) {
                skipped.add(new AnnualReportBatchGenerateResponse.SkippedCustomer(customerId, ex.getMessage()));
            }
        }

        AnnualReportBatchGenerateResponse result = new AnnualReportBatchGenerateResponse(
                request.reportYear(), uniqueIds.size(), createdItems.size(), skipped.size(), createdItems, skipped);

        auditService.record(user.tenantId(), user.userId(), "ANNUAL_REPORT_BATCH_CREATE", "ANNUAL_REPORT_BATCH",
                request.reportYear() + ":" + idempotencyKey, null,
                Map.of("reportYear", request.reportYear(), "requestedCount", uniqueIds.size(),
                        "createdCount", createdItems.size(), "skippedCount", skipped.size()),
                "批量生成客户年度购买轨迹 H5 快照");
        String storedIds = createdItems.stream()
                .map(item -> Long.toString(item.id()))
                .collect(Collectors.joining(","));
        idempotencyService.recordSuccess(user.tenantId(), BATCH_SCOPE, idempotencyKey, "ANNUAL_REPORT_BATCH",
                storedIds.isEmpty() ? "NONE" : storedIds, payloadHash);
        return result;
    }

    @Transactional(readOnly = true)
    public PageResponse<AnnualReportItemResponse> search(CurrentUser user, Integer reportYear, Long customerId,
                                                         String status, int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        int offset = (page - 1) * size;
        List<AnnualReportItemResponse> items = repository.searchReports(
                        user.tenantId(), query.scopeType(), query.teamIds(), query.selfId(),
                        reportYear, customerId, status, offset, size)
                .stream()
                .map(this::toItemResponse)
                .toList();
        long total = repository.countSearchReports(user.tenantId(), query.scopeType(), query.teamIds(),
                query.selfId(), reportYear, customerId, status);
        return new PageResponse<>(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public AnnualReportSummaryResponse summary(CurrentUser user, int reportYear) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return emptySummary(reportYear);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        AnnualReportCustomerFact.ReportSummaryRow row = repository.summarizeReports(
                user.tenantId(), query.scopeType(), query.teamIds(), query.selfId(), reportYear);
        if (row == null) {
            return emptySummary(reportYear);
        }
        long avgDuration = row.totalSessions() > 0
                ? row.totalVisibleDurationMs() / row.totalSessions()
                : 0L;
        List<AnnualReportSummaryResponse.SceneFunnelItem> funnel = List.of(
                new AnnualReportSummaryResponse.SceneFunnelItem(1, SCENE_LABELS[0], row.reachedScene1()),
                new AnnualReportSummaryResponse.SceneFunnelItem(2, SCENE_LABELS[1], row.reachedScene2()),
                new AnnualReportSummaryResponse.SceneFunnelItem(3, SCENE_LABELS[2], row.reachedScene3()),
                new AnnualReportSummaryResponse.SceneFunnelItem(4, SCENE_LABELS[3], row.reachedScene4()),
                new AnnualReportSummaryResponse.SceneFunnelItem(5, SCENE_LABELS[4], row.reachedScene5()),
                new AnnualReportSummaryResponse.SceneFunnelItem(6, SCENE_LABELS[5], row.reachedScene6())
        );
        return new AnnualReportSummaryResponse(
                reportYear,
                row.totalReports(),
                row.activeReports(),
                row.openedReports(),
                row.completedReports(),
                row.totalSessions(),
                avgDuration,
                row.posterReports(),
                row.bookingIntentReports(),
                funnel
        );
    }

    @Transactional(readOnly = true)
    public List<AnnualReportSessionResponse> listSessions(CurrentUser user, long reportId, int limit) {
        AnnualReport report = requireVisibleReport(user, reportId);
        return repository.listSessions(user.tenantId(), report.id(), limit).stream()
                .map(this::toSessionResponse)
                .toList();
    }

    @Transactional
    public AnnualReportItemResponse disable(CurrentUser user, long reportId,
                                            AnnualReportDisableRequest request, String idempotencyKey) {
        String payloadHash = requestPayload(reportId, request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), DISABLE_SCOPE, idempotencyKey, payloadHash);
        if (replay.isPresent()) {
            if (!Long.toString(reportId).equals(replay.orElseThrow().getResourceId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "幂等结果与目标报告不一致");
            }
            return toItemResponse(requireVisibleReport(user, reportId));
        }

        AnnualReport before = repository.findByIdForUpdate(user.tenantId(), reportId).orElseThrow(this::notFound);
        scopeSupport.requireVisible(user.tenantId(), before.customerId(), user);
        if (before.version() != request.version()) {
            throw new BusinessException(ErrorCode.CONFLICT, "年度报告已被其他人更新，请刷新后重试");
        }
        if (!"ACTIVE".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "年度报告已停用，不能重复停用");
        }
        String reason = masker.required(request.reason(), "停用原因", 512);
        if (repository.disableReport(user.tenantId(), reportId, before.version(), reason,
                user.userId(), Instant.now()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "年度报告已被其他人更新，请刷新后重试");
        }
        AnnualReport after = requireVisibleReport(user, reportId);
        auditService.record(user.tenantId(), user.userId(), "ANNUAL_REPORT_DISABLE", "ANNUAL_REPORT",
                Long.toString(reportId),
                Map.of("status", before.status(), "version", before.version()),
                Map.of("status", after.status(), "version", after.version()),
                reason);
        idempotencyService.recordSuccess(user.tenantId(), DISABLE_SCOPE, idempotencyKey, "ANNUAL_REPORT",
                Long.toString(reportId), payloadHash);
        return toItemResponse(after);
    }

    private AnnualReport generateForCustomer(CurrentUser actor, Customer customer, int reportYear,
                                             String toneCode, String genderHint, String customButlerNote,
                                             boolean disablePreviousActive) {
        long tenantId = actor.tenantId();
        Instant now = Instant.now();
        Instant yearStart = LocalDate.of(reportYear, 1, 1).atStartOfDay(SHANGHAI).toInstant();
        Instant yearEnd = LocalDate.of(reportYear + 1, 1, 1).atStartOfDay(SHANGHAI).toInstant();

        List<AnnualReportCustomerFact.OrderRow> orders = repository.findYearPaidOrders(
                tenantId, customer.getId(), yearStart, yearEnd);
        List<Long> orderIds = orders.stream().map(AnnualReportCustomerFact.OrderRow::orderId).toList();
        List<AnnualReportCustomerFact.OrderItemRow> orderItems = orderIds.isEmpty()
                ? List.of() : repository.findOrderItemsByOrderIds(tenantId, orderIds);
        List<AnnualReportCustomerFact.EntitlementRow> yearEntitlements = orderIds.isEmpty()
                ? List.of() : repository.findYearOrderEntitlements(tenantId, customer.getId(), orderIds);
        List<AnnualReportCustomerFact.EntitlementRow> availableEntitlements = repository.findAvailableEntitlements(
                tenantId, customer.getId());
        List<AnnualReportCustomerFact.RedemptionRow> redemptions = repository.findYearPostedRedemptions(
                tenantId, customer.getId(), yearStart, yearEnd);
        List<AnnualReportCustomerFact.FollowupRow> followups = repository.findYearFollowups(
                tenantId, customer.getId(), yearStart, yearEnd);
        List<String> tagCodes = repository.findCustomerTagCodes(tenantId, customer.getId());
        String butlerName = repository.findUserDisplayName(tenantId, customer.getOwnerStaffId())
                .map(name -> masker.mask(name, 64))
                .orElse("专属管家");

        // 计算首次相遇时间（取客户建档、首次添加、首单时间、历史最早实付单中最早者）
        Instant earliestPaid = repository.findEarliestPaidOrderAt(tenantId, customer.getId()).orElse(null);
        Instant firstMeet = earliestOf(customer.getFirstAddedAt(), customer.getFirstOrderAt(),
                earliestPaid, customer.getCreatedAt(), now);
        LocalDate firstMeetLocal = firstMeet.atZone(SHANGHAI).toLocalDate();
        LocalDate todayLocal = now.atZone(SHANGHAI).toLocalDate();
        long daysTogether = Math.max(1L, ChronoUnit.DAYS.between(firstMeetLocal, todayLocal) + 1L);

        // 严格整数分汇总全年净消费（有效实付扣成功退款，不低于 0）
        long netSpendFen = 0L;
        for (AnnualReportCustomerFact.OrderRow order : orders) {
            long net = Math.max(0L, order.paidFen() - order.refundedFen());
            netSpendFen += net;
        }

        // 整理订单明细、状态与品类统计
        Map<Long, List<AnnualReportCustomerFact.OrderItemRow>> itemsByOrder = orderItems.stream()
                .collect(Collectors.groupingBy(AnnualReportCustomerFact.OrderItemRow::orderId));
        Map<Long, List<AnnualReportCustomerFact.EntitlementRow>> entsByOrder = yearEntitlements.stream()
                .collect(Collectors.groupingBy(AnnualReportCustomerFact.EntitlementRow::orderId));

        List<PublicAnnualReportResponse.OrderSnapshot> orderSnapshots = new ArrayList<>();
        Set<String> distinctCategories = new LinkedHashSet<>();

        for (AnnualReportCustomerFact.OrderRow order : orders) {
            Instant effectiveTime = firstNonNull(order.paidAt(), order.orderedAt(), now);
            ZonedDateTime zdt = effectiveTime.atZone(SHANGHAI);
            List<AnnualReportCustomerFact.OrderItemRow> items = itemsByOrder.getOrDefault(order.orderId(), List.of());
            List<AnnualReportCustomerFact.EntitlementRow> orderEnts = entsByOrder.getOrDefault(order.orderId(), List.of());

            String category = orderEnts.stream()
                    .map(AnnualReportCustomerFact.EntitlementRow::categoryCode)
                    .filter(code -> code != null && !code.isBlank())
                    .map(this::translateCategory)
                    .findFirst()
                    .orElse("臻选美学");
            distinctCategories.add(category);

            String title = items.isEmpty()
                    ? ("美学私享套餐 · " + category)
                    : masker.mask(items.getFirst().skuNameSnapshot(), 80);
            int remainInOrder = orderEnts.stream()
                    .filter(e -> "AVAILABLE".equals(e.status()))
                    .mapToInt(AnnualReportCustomerFact.EntitlementRow::availableCount)
                    .sum();
            boolean hasOpen = remainInOrder > 0;
            String statusText = hasOpen ? ("余 " + remainInOrder + " 次待约") : "已核销完";

            List<PublicAnnualReportResponse.OrderSkuSnapshot> skuSnapshots = buildOrderSkus(items, orderEnts);
            String channel = "YOUZAN".equalsIgnoreCase(order.sourceSystem()) ? "有赞官方商城" : "鲸选美学私享订购";

            orderSnapshots.add(new PublicAnnualReportResponse.OrderSnapshot(
                    maskOrderNo(order.externalOrderNo() != null && !order.externalOrderNo().isBlank()
                            ? order.externalOrderNo() : order.orderNo()),
                    zdt.format(DATE_FMT),
                    zdt.format(MD_FMT),
                    zdt.format(TIME_FMT),
                    seasonOf(zdt.getMonthValue()),
                    title,
                    category,
                    Math.max(0L, order.paidFen() - order.refundedFen()),
                    statusText,
                    hasOpen,
                    channel,
                    skuSnapshots
            ));
        }

        // 深夜/最晚活跃时刻（优先取当年订单实付时刻，若当年无订单则取当年跟进时刻）
        MidnightComputation midnightComp = computeMidnight(orders, orderSnapshots, followups, now);

        // 年度挚爱项目与四大美学维度（结合权益发放次数 + 正式核销次数）
        FavoriteComputation favoriteComp = computeFavorite(yearEntitlements, redemptions);

        // 履约成就与剩余权益宝库
        int redeemCount = redemptions.stream().mapToInt(AnnualReportCustomerFact.RedemptionRow::count).sum();
        int institutionCount = (int) redemptions.stream()
                .map(AnnualReportCustomerFact.RedemptionRow::institutionId)
                .distinct()
                .count();
        int remainingTotal = availableEntitlements.stream()
                .mapToInt(AnnualReportCustomerFact.EntitlementRow::availableCount)
                .sum();
        String expireDate = availableEntitlements.stream()
                .map(AnnualReportCustomerFact.EntitlementRow::validTo)
                .filter(d -> d != null)
                .max(LocalDate::compareTo)
                .map(d -> d.format(DATE_FMT))
                .orElse("以权益卡面为准");

        Map<String, Integer> remainByProject = new LinkedHashMap<>();
        for (AnnualReportCustomerFact.EntitlementRow ent : availableEntitlements) {
            String pname = masker.mask(ent.projectName(), 80);
            remainByProject.merge(pname, ent.availableCount(), Integer::sum);
        }
        List<PublicAnnualReportResponse.VaultItem> vaultItems = remainByProject.entrySet().stream()
                .limit(6)
                .map(entry -> new PublicAnnualReportResponse.VaultItem(
                        entry.getKey(),
                        "请联系专属管家预约合作臻选机构",
                        "余 " + entry.getValue() + " 次"))
                .toList();

        // 脱敏客户称呼与等级称号
        String displayName = resolveSafeDisplayName(customer);
        String tierCode = customer.getTierCode() == null || customer.getTierCode().isBlank() ? "B" : customer.getTierCode();
        String tierLabel = tierLabelOf(tierCode);
        String sealText = "臻选" + tierCode.toUpperCase(Locale.ROOT);
        String memberNo = "NO. " + masker.mask(customer.getCustomerNo(), 36);

        String safeCustomNote = copyEngine.sanitizeCustomNote(customButlerNote);
        AnnualReportCopyEngine.ToneDecision tone = copyEngine.resolveTone(
                toneCode, genderHint, tagCodes, tierCode, daysTogether, favoriteComp.projectName());

        PublicAnnualReportResponse.Copywriting copy = copyEngine.generate(new AnnualReportCopyEngine.CopyInput(
                reportYear,
                tone.toneCode(),
                genderHint == null ? "UNKNOWN" : genderHint,
                displayName,
                butlerName,
                firstMeetLocal.format(DATE_FMT),
                daysTogether,
                orders.size(),
                redeemCount,
                remainingTotal,
                favoriteComp.projectName(),
                midnightComp.fromOrder(),
                midnightComp.snapshot().date(),
                midnightComp.snapshot().time(),
                midnightComp.snapshot().orderTitle(),
                safeCustomNote
        ));

        PublicAnnualReportResponse publicSnapshot = new PublicAnnualReportResponse(
                reportYear,
                memberNo,
                displayName,
                tierLabel,
                sealText,
                butlerName,
                firstMeetLocal.format(DATE_FMT),
                daysTogether,
                orders.size(),
                netSpendFen,
                Math.max(distinctCategories.size(), favoriteComp.dimensions().isEmpty() ? 0 : 1),
                redeemCount,
                institutionCount,
                followups.size(),
                tone.toneCode(),
                tone.toneLabel(),
                tone.reason(),
                copy,
                midnightComp.snapshot(),
                favoriteComp.snapshot(),
                new PublicAnnualReportResponse.VaultSnapshot(remainingTotal, expireDate, vaultItems),
                orderSnapshots
        );

        String snapshotJson = writeJson(publicSnapshot);
        if (disablePreviousActive) {
            repository.disableActiveForCustomerYear(tenantId, customer.getId(), reportYear,
                    "生成新版年度快照自动停用旧链接", actor.userId(), now);
        }

        String shareKey = newShareKey();
        AnnualReport entity = new AnnualReport(
                null,
                tenantId,
                customer.getId(),
                customer.getOwnerStaffId(),
                customer.getTeamId(),
                actor.userId(),
                reportYear,
                shareKey,
                tone.toneCode(),
                tone.reason(),
                displayName,
                tierLabel,
                butlerName,
                orders.size(),
                netSpendFen,
                redeemCount,
                remainingTotal,
                snapshotJson,
                "ACTIVE",
                null,
                null,
                null,
                null,
                null,
                0L
        );
        repository.insertReport(entity);
        return repository.findByShareKey(shareKey).orElseThrow();
    }

    private List<PublicAnnualReportResponse.OrderSkuSnapshot> buildOrderSkus(
            List<AnnualReportCustomerFact.OrderItemRow> items,
            List<AnnualReportCustomerFact.EntitlementRow> ents) {
        if (!ents.isEmpty()) {
            return ents.stream()
                    .map(e -> new PublicAnnualReportResponse.OrderSkuSnapshot(
                            masker.mask(e.projectName(), 80), e.grantedCount() + " 次"))
                    .toList();
        }
        return items.stream()
                .map(i -> new PublicAnnualReportResponse.OrderSkuSnapshot(
                        masker.mask(i.skuNameSnapshot(), 80), i.purchaseQuantity() + " 份"))
                .toList();
    }

    private MidnightComputation computeMidnight(List<AnnualReportCustomerFact.OrderRow> orders,
                                                List<PublicAnnualReportResponse.OrderSnapshot> orderSnapshots,
                                                List<AnnualReportCustomerFact.FollowupRow> followups,
                                                Instant fallbackNow) {
        List<PublicAnnualReportResponse.HourPoint> points = new ArrayList<>();
        int bestScore = -1;
        ZonedDateTime bestZdt = fallbackNow.atZone(SHANGHAI);
        String bestTitle = "年度美学光采之约";
        boolean fromOrder = false;

        for (int i = 0; i < orders.size(); i++) {
            AnnualReportCustomerFact.OrderRow o = orders.get(i);
            Instant ts = firstNonNull(o.paidAt(), o.orderedAt(), fallbackNow);
            ZonedDateTime zdt = ts.atZone(SHANGHAI);
            int nightScore = nightPriorityScore(zdt.getHour(), zdt.getMinute());
            double hourDecimal = zdt.getHour() + zdt.getMinute() / 60.0;
            String title = i < orderSnapshots.size() ? orderSnapshots.get(i).title() : "臻选美学项目";
            points.add(new PublicAnnualReportResponse.HourPoint(
                    Math.round(hourDecimal * 100.0) / 100.0, 0.72, false, zdt.format(TIME_FMT) + " 下单"));
            if (nightScore > bestScore) {
                bestScore = nightScore;
                bestZdt = zdt;
                bestTitle = title;
                fromOrder = true;
            }
        }

        if (!fromOrder && !followups.isEmpty()) {
            for (AnnualReportCustomerFact.FollowupRow f : followups) {
                ZonedDateTime zdt = f.contactAt().atZone(SHANGHAI);
                int nightScore = nightPriorityScore(zdt.getHour(), zdt.getMinute());
                double hourDecimal = zdt.getHour() + zdt.getMinute() / 60.0;
                points.add(new PublicAnnualReportResponse.HourPoint(
                        Math.round(hourDecimal * 100.0) / 100.0, 0.65, false, zdt.format(TIME_FMT) + " 管家跟进"));
                if (nightScore > bestScore) {
                    bestScore = nightScore;
                    bestZdt = zdt;
                }
            }
        }

        double bestHour = Math.round((bestZdt.getHour() + bestZdt.getMinute() / 60.0) * 100.0) / 100.0;
        List<PublicAnnualReportResponse.HourPoint> finalPoints = new ArrayList<>();
        boolean marked = false;
        for (PublicAnnualReportResponse.HourPoint pt : points) {
            if (!marked && Math.abs(pt.hour() - bestHour) < 0.02) {
                finalPoints.add(new PublicAnnualReportResponse.HourPoint(pt.hour(), 1.0, true, pt.label()));
                marked = true;
            } else {
                finalPoints.add(pt);
            }
        }
        if (!marked) {
            finalPoints.add(new PublicAnnualReportResponse.HourPoint(bestHour, 1.0, true, bestZdt.format(TIME_FMT)));
        }

        String eventType = fromOrder ? "CRM / 有赞实付下单时刻（Asia/Shanghai）" : "专属管家沟通记录时刻（Asia/Shanghai）";
        String peakLabel = computePeakLabel(finalPoints);
        return new MidnightComputation(
                fromOrder,
                new PublicAnnualReportResponse.MidnightSnapshot(
                        bestZdt.format(TIME_FMT),
                        bestZdt.format(DATE_FMT),
                        bestTitle,
                        eventType,
                        peakLabel,
                        finalPoints.stream().limit(12).toList()
                )
        );
    }

    /**
     * 深夜权重：21:00–03:59 视为深夜档（越接近凌晨 02:00 分值越高），其余时段按晚间靠后优先。
     */
    private int nightPriorityScore(int hour, int minute) {
        int shiftedHour = hour < 4 ? hour + 24 : hour;
        return shiftedHour * 60 + minute;
    }

    private String computePeakLabel(List<PublicAnnualReportResponse.HourPoint> points) {
        if (points.isEmpty()) {
            return "高频活跃时段：全天从容安排";
        }
        long nightCount = points.stream().filter(p -> p.hour() >= 20.0 || p.hour() < 4.0).count();
        long afternoonCount = points.stream().filter(p -> p.hour() >= 12.0 && p.hour() < 20.0).count();
        if (nightCount >= afternoonCount && nightCount > 0) {
            int pct = (int) Math.round(nightCount * 100.0 / points.size());
            return "高频活跃时段：20:00–02:00（占比 " + pct + "%）";
        }
        if (afternoonCount > 0) {
            int pct = (int) Math.round(afternoonCount * 100.0 / points.size());
            return "高频活跃时段：12:00–20:00（占比 " + pct + "%）";
        }
        return "高频活跃时段：08:00–12:00 晨间焕美";
    }

    private FavoriteComputation computeFavorite(List<AnnualReportCustomerFact.EntitlementRow> entitlements,
                                                List<AnnualReportCustomerFact.RedemptionRow> redemptions) {
        Map<String, Integer> projectCounts = new HashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();

        for (AnnualReportCustomerFact.EntitlementRow e : entitlements) {
            String pName = masker.mask(e.projectName(), 80);
            projectCounts.merge(pName, Math.max(1, e.grantedCount()), Integer::sum);
            categoryCounts.merge(translateCategory(e.categoryCode()), Math.max(1, e.grantedCount()), Integer::sum);
        }
        for (AnnualReportCustomerFact.RedemptionRow r : redemptions) {
            String pName = masker.mask(r.projectName(), 80);
            projectCounts.merge(pName, Math.max(1, r.count()), Integer::sum);
            categoryCounts.merge(translateCategory(r.categoryCode()), Math.max(1, r.count()), Integer::sum);
        }

        List<Map.Entry<String, Integer>> sortedProjects = projectCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();

        String topProject = sortedProjects.isEmpty() ? "鲸选定制美学护理" : sortedProjects.getFirst().getKey();
        int topCount = sortedProjects.isEmpty() ? 1 : sortedProjects.getFirst().getValue();
        String beatText = topCount >= 5 ? "高频挚爱 · 稳居年度核心清单" : "年度精选 · 专属定制节奏";

        List<PublicAnnualReportResponse.TopProjectItem> top3 = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sortedProjects.size()); i++) {
            Map.Entry<String, Integer> entry = sortedProjects.get(i);
            top3.add(new PublicAnnualReportResponse.TopProjectItem(
                    String.format(Locale.ROOT, "%02d", i + 1), entry.getKey(), entry.getValue() + " 次"));
        }
        if (top3.isEmpty()) {
            top3.add(new PublicAnnualReportResponse.TopProjectItem("01", topProject, "定制规划中"));
        }

        int maxCat = categoryCounts.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        List<PublicAnnualReportResponse.DimensionItem> dimensions = categoryCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(4)
                .map(entry -> new PublicAnnualReportResponse.DimensionItem(
                        entry.getKey(),
                        Math.min(96, Math.max(36, (int) Math.round(entry.getValue() * 92.0 / maxCat)))))
                .toList();
        if (dimensions.isEmpty()) {
            dimensions = List.of(
                    new PublicAnnualReportResponse.DimensionItem("光学焕亮", 78),
                    new PublicAnnualReportResponse.DimensionItem("深层补水", 68),
                    new PublicAnnualReportResponse.DimensionItem("肌底修护", 56)
            );
        }

        List<String> tags = new ArrayList<>();
        for (PublicAnnualReportResponse.DimensionItem dim : dimensions) {
            tags.add(dim.name());
        }
        if (tags.size() < 3) {
            tags.add("院线臻选");
            tags.add("专属维养");
        }

        return new FavoriteComputation(
                topProject,
                dimensions,
                new PublicAnnualReportResponse.FavoriteSnapshot(topProject, beatText, tags, dimensions, top3)
        );
    }

    private String translateCategory(String code) {
        if (code == null || code.isBlank()) {
            return "臻选美学";
        }
        String upper = code.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "OPTICAL", "LASER", "PHOTO" -> "光电焕亮";
            case "HYDRO", "INJECTION", "MESO" -> "深层水光";
            case "ANTI_AGING", "LIFTING", "CONTOUR" -> "轮廓紧致";
            case "REPAIR", "SENSITIVE", "SKIN_CARE" -> "敏肌修护";
            default -> masker.mask(code.trim(), 24);
        };
    }

    private String tierLabelOf(String tierCode) {
        return switch (tierCode.toUpperCase(Locale.ROOT)) {
            case "S" -> "臻选 S 级缪斯";
            case "A" -> "鎏金 A 级尊享";
            case "B" -> "星耀 B 级会员";
            case "C" -> "轻盈 C 级挚友";
            default -> "鲸选美学会员";
        };
    }

    private String seasonOf(int month) {
        if (month >= 3 && month <= 5) {
            return "春";
        }
        if (month >= 6 && month <= 8) {
            return "夏";
        }
        if (month >= 9 && month <= 11) {
            return "秋";
        }
        return "冬";
    }

    private String resolveSafeDisplayName(Customer customer) {
        String candidate = firstNonBlank(customer.getNickname(), customer.getDisplayName(), "尊贵会员");
        return masker.mask(candidate, 48);
    }

    private String maskOrderNo(String raw) {
        if (raw == null || raw.isBlank()) {
            return "JX****0000";
        }
        String clean = raw.strip();
        if (clean.length() <= 8) {
            return clean.substring(0, Math.min(2, clean.length())) + "****";
        }
        return clean.substring(0, 6) + "****" + clean.substring(clean.length() - 4);
    }

    private Instant earliestOf(Instant... instants) {
        Instant best = null;
        for (Instant item : instants) {
            if (item != null && (best == null || item.isBefore(best))) {
                best = item;
            }
        }
        return best != null ? best : Instant.now();
    }

    private Instant firstNonNull(Instant... values) {
        for (Instant value : values) {
            if (value != null) {
                return value;
            }
        }
        return Instant.now();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "尊贵会员";
    }

    private AnnualReport requireVisibleReport(CurrentUser user, long reportId) {
        AnnualReport report = repository.findById(user.tenantId(), reportId).orElseThrow(this::notFound);
        scopeSupport.requireVisible(user.tenantId(), report.customerId(), user);
        return report;
    }

    private AnnualReportItemResponse toItemResponse(AnnualReport report) {
        return new AnnualReportItemResponse(
                report.id(),
                report.customerId(),
                report.ownerStaffId(),
                report.reportYear(),
                report.shareKey(),
                buildPublicLink(report.shareKey()),
                report.toneCode(),
                report.toneReason(),
                report.displayNameSnapshot(),
                report.tierLabelSnapshot(),
                report.butlerNameSnapshot(),
                report.orderCount(),
                report.netSpendFen(),
                report.redeemCount(),
                report.remainingEntitlementCount(),
                report.status(),
                report.createdAt(),
                report.version(),
                0L,
                0L,
                0,
                0,
                false,
                false,
                null
        );
    }

    private AnnualReportItemResponse toItemResponse(AnnualReportCustomerFact.ReportListRow row) {
        return new AnnualReportItemResponse(
                row.id(),
                row.customerId(),
                row.ownerStaffId(),
                row.reportYear(),
                row.shareKey(),
                buildPublicLink(row.shareKey()),
                row.toneCode(),
                row.toneReason(),
                row.displayNameSnapshot(),
                row.tierLabelSnapshot(),
                row.butlerNameSnapshot(),
                row.orderCount(),
                row.netSpendFen(),
                row.redeemCount(),
                row.remainingEntitlementCount(),
                row.status(),
                row.createdAt(),
                row.version(),
                row.visitCount(),
                row.totalVisibleDurationMs(),
                row.maxSceneReached(),
                row.maxViewedSceneCount(),
                row.generatedPoster(),
                row.clickedBookButler(),
                row.lastActiveAt()
        );
    }

    private AnnualReportSessionResponse toSessionResponse(AnnualReportVisitSession session) {
        List<Integer> scenes = readJson(session.viewedScenesJson(), new TypeReference<>() {
        }, List.of());
        Map<String, Long> durations = readJson(session.sceneDurationsJson(), new TypeReference<>() {
        }, Map.of());
        return new AnnualReportSessionResponse(
                session.id(),
                session.reportId(),
                session.sessionKey(),
                session.firstOpenAt(),
                session.lastActiveAt(),
                session.visibleDurationMs(),
                session.maxSceneReached(),
                session.viewedSceneCount(),
                scenes,
                durations,
                session.ignitedLamp(),
                session.openedOrderCount(),
                session.toggledPrivacy(),
                session.generatedPoster(),
                session.clickedBookButler(),
                session.bookingTaskId(),
                session.clientDevice()
        );
    }

    private AnnualReportSummaryResponse emptySummary(int reportYear) {
        List<AnnualReportSummaryResponse.SceneFunnelItem> funnel = new ArrayList<>();
        for (int i = 0; i < SCENE_LABELS.length; i++) {
            funnel.add(new AnnualReportSummaryResponse.SceneFunnelItem(i + 1, SCENE_LABELS[i], 0L));
        }
        return new AnnualReportSummaryResponse(reportYear, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, funnel);
    }

    private AnnualReportBatchGenerateResponse readBatchReplay(long tenantId, String resourceIds, int reportYear) {
        if (resourceIds == null || resourceIds.isBlank() || "NONE".equals(resourceIds)) {
            return new AnnualReportBatchGenerateResponse(reportYear, 0, 0, 0, List.of(), List.of());
        }
        List<AnnualReportItemResponse> items = new ArrayList<>();
        for (String token : resourceIds.split(",")) {
            if (!token.isBlank()) {
                long id = Long.parseLong(token.trim());
                repository.findById(tenantId, id).map(this::toItemResponse).ifPresent(items::add);
            }
        }
        return new AnnualReportBatchGenerateResponse(reportYear, items.size(), items.size(), 0, items, List.of());
    }

    private String buildPublicLink(String shareKey) {
        return publicOrigin() + "/h5/annual-reports/" + shareKey;
    }

    private String publicOrigin() {
        try {
            if (sidebarProperties != null && sidebarProperties.sidebarUrl() != null
                    && !sidebarProperties.sidebarUrl().isBlank()) {
                URI uri = URI.create(sidebarProperties.sidebarUrl().strip());
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                if (!host.isEmpty() && ("https".equals(scheme) || "http".equals(scheme))) {
                    return scheme + "://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
                }
            }
        } catch (Exception ignored) {
            // 配置不可解析时回退相对路径，避免阻断内部快照生成
        }
        return "";
    }

    private String newShareKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "年度报告快照序列化失败");
        }
    }

    private <T> T readJson(String raw, TypeReference<T> typeRef, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(raw, typeRef);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private long parseResourceId(IdempotencyRecord record) {
        try {
            return Long.parseLong(record.getResourceId());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等记录无效");
        }
    }

    private String requestPayload(Object... values) {
        return java.util.Arrays.deepToString(values);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "年度报告不存在或不可见");
    }

    private record MidnightComputation(boolean fromOrder, PublicAnnualReportResponse.MidnightSnapshot snapshot) {
    }

    private record FavoriteComputation(String projectName,
                                       List<PublicAnnualReportResponse.DimensionItem> dimensions,
                                       PublicAnnualReportResponse.FavoriteSnapshot snapshot) {
    }
}
