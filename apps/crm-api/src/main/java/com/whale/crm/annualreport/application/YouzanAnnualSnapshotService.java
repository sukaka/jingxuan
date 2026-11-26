package com.whale.crm.annualreport.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.annualreport.adapter.YouzanAnnualPullClient;
import com.whale.crm.annualreport.api.dto.PublicAnnualReportResponse;
import com.whale.crm.annualreport.api.dto.YouzanAnnualProfileItemResponse;
import com.whale.crm.annualreport.api.dto.YouzanAnnualSnapshotSummaryResponse;
import com.whale.crm.annualreport.api.dto.YouzanAnnualSnapshotSyncRequest;
import com.whale.crm.annualreport.api.dto.YouzanAnnualSnapshotSyncResponse;
import com.whale.crm.annualreport.config.AnnualH5Properties;
import com.whale.crm.annualreport.domain.YouzanAnnualOrderSnapshot;
import com.whale.crm.annualreport.domain.YouzanAnnualProfile;
import com.whale.crm.annualreport.domain.YouzanAnnualSnapshotRepository;
import com.whale.crm.customer.application.MobileSupport;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.BlindIndex;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 有赞历史订单只读快照拉取与年终 H5 只读视图构建服务。
 * 严格红线：
 * 1. 只读快照写入 crm_youzan_annual_profile / crm_youzan_annual_order_snapshot，绝不写入 crm_order 或触发权益发放；
 * 2. OpenID / UnionID / 手机号全部经 BlindIndex 计算盲索引，不落明文、不入日志；
 * 3. 单次拉取自动按 <=89 天切分时间窗，遵循有赞官方 90 天分页上限。
 */
@Service
public class YouzanAnnualSnapshotService {

    public static final String WX_OPENID_HASH_PREFIX = "wx:openid:";
    public static final String WX_UNIONID_HASH_PREFIX = "wx:unionid:";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter MD_FMT = DateTimeFormatter.ofPattern("MM.dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SYNC_SCOPE = "youzan.annual.snapshot.sync";
    private static final int MAX_WINDOW_DAYS = 89;
    private static final int MAX_SPAN_DAYS = 370;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final YouzanAnnualPullClient pullClient;
    private final YouzanAnnualSnapshotRepository repository;
    private final AnnualReportCopyEngine copyEngine;
    private final MobileSupport mobileSupport;
    private final BlindIndex blindIndex;
    private final QuickReplyTextMasker masker;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final AnnualH5Properties properties;
    private final ObjectMapper objectMapper;

    public YouzanAnnualSnapshotService(YouzanAnnualPullClient pullClient,
                                       YouzanAnnualSnapshotRepository repository,
                                       AnnualReportCopyEngine copyEngine,
                                       MobileSupport mobileSupport,
                                       BlindIndex blindIndex,
                                       QuickReplyTextMasker masker,
                                       IdempotencyService idempotencyService,
                                       AuditService auditService,
                                       AnnualH5Properties properties,
                                       ObjectMapper objectMapper) {
        this.pullClient = pullClient;
        this.repository = repository;
        this.copyEngine = copyEngine;
        this.mobileSupport = mobileSupport;
        this.blindIndex = blindIndex;
        this.masker = masker;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public YouzanAnnualSnapshotSyncResponse syncSnapshots(CurrentUser user,
                                                          YouzanAnnualSnapshotSyncRequest request,
                                                          String idempotencyKey) {
        requireEmployee(user);
        LocalDate startDate = request.startDate() == null || request.startDate().isBlank()
                ? LocalDate.of(LocalDate.now(SHANGHAI).getYear(), 1, 1)
                : LocalDate.parse(request.startDate());
        LocalDate endDate = request.endDate() == null || request.endDate().isBlank()
                ? LocalDate.now(SHANGHAI)
                : LocalDate.parse(request.endDate());
        if (endDate.isBefore(startDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) > MAX_SPAN_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "单次拉取时间跨度不能超过 370 天，请分年拉取");
        }

        String shopId = request.shopId().trim();
        boolean enrich = request.enrichBuyerProfile() == null || request.enrichBuyerProfile();
        int maxPages = request.maxPagesPerWindow() == null ? 20 : request.maxPagesPerWindow();
        String payloadHash = IdempotencyService.sha256(
                shopId + "|" + startDate + "|" + endDate + "|" + enrich + "|" + maxPages);

        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), SYNC_SCOPE, idempotencyKey, payloadHash);
        if (replay.isPresent()) {
            return parseReplay(replay.orElseThrow().getResourceId(), shopId, startDate, endDate);
        }

        Instant now = Instant.now();
        int windowsPulled = 0;
        int ordersSynced = 0;
        int paidOrdersSynced = 0;
        Map<String, BuyerSeed> buyers = new LinkedHashMap<>();
        Set<String> warnings = new LinkedHashSet<>();

        LocalDate cursorStart = startDate;
        while (!cursorStart.isAfter(endDate)) {
            LocalDate cursorEnd = cursorStart.plusDays(MAX_WINDOW_DAYS);
            if (cursorEnd.isAfter(endDate)) {
                cursorEnd = endDate;
            }
            Instant winStart = cursorStart.atStartOfDay(SHANGHAI).toInstant();
            Instant winEnd = cursorEnd.plusDays(1).atStartOfDay(SHANGHAI).toInstant().minusMillis(1);
            windowsPulled++;

            for (int pageNo = 1; pageNo <= maxPages; pageNo++) {
                YouzanAnnualPullClient.PulledOrderPage page = pullClient.pullOrderPage(
                        user.tenantId(), shopId, winStart, winEnd, pageNo, DEFAULT_PAGE_SIZE);
                for (YouzanAnnualPullClient.PulledOrder pulled : page.orders()) {
                    if (pulled.tid() == null || pulled.tid().isBlank()
                            || pulled.yzOpenId() == null || pulled.yzOpenId().isBlank()) {
                        continue;
                    }
                    String safeTitle = masker.mask(pulled.titleSummary(), 160);
                    List<YouzanAnnualPullClient.PulledOrderItem> safeItems = pulled.items().stream()
                            .map(item -> new YouzanAnnualPullClient.PulledOrderItem(
                                    item.itemId(),
                                    item.skuId(),
                                    masker.mask(item.title(), 100),
                                    item.skuProps() == null ? null : masker.mask(item.skuProps(), 80),
                                    Math.max(1, item.num()),
                                    Math.max(0L, item.priceFen())))
                            .toList();
                    repository.upsertOrderSnapshot(new YouzanAnnualOrderSnapshot(
                            null,
                            user.tenantId(),
                            shopId,
                            pulled.tid(),
                            pulled.yzOpenId(),
                            pulled.orderStatus(),
                            pulled.paid(),
                            pulled.orderedAt(),
                            pulled.paidAt(),
                            Math.max(0L, pulled.paymentFen()),
                            Math.max(0L, pulled.refundedFen()),
                            safeTitle,
                            writeJson(safeItems),
                            now,
                            null,
                            null
                    ));
                    ordersSynced++;
                    if (pulled.paid()) {
                        paidOrdersSynced++;
                    }
                    buyers.compute(pulled.yzOpenId(), (k, existing) -> mergeSeed(existing, pulled));
                }
                if (!page.hasMore()) {
                    break;
                }
                if (pageNo == maxPages) {
                    warnings.add("窗口 " + cursorStart + " 至 " + cursorEnd + " 超过单窗页数上限（" + maxPages + "页），建议缩小日期范围分批补拉");
                }
            }
            cursorStart = cursorEnd.plusDays(1);
        }

        int buyersEnriched = 0;
        int openIdBound = 0;
        int unionIdBound = 0;
        int linkedCustomerCount = 0;

        for (Map.Entry<String, BuyerSeed> entry : buyers.entrySet()) {
            String yzOpenId = entry.getKey();
            BuyerSeed seed = entry.getValue();
            YouzanAnnualPullClient.BuyerProfileEnrichment extra = YouzanAnnualPullClient.BuyerProfileEnrichment.empty(null);
            if (enrich) {
                YouzanAnnualPullClient.BuyerProfileEnrichment fetched = pullClient.fetchBuyerProfile(
                        user.tenantId(), shopId, yzOpenId, seed.fansType(), seed.fansId(), seed.mobile());
                if (fetched != null) {
                    extra = fetched;
                }
                if (extra.warning() != null && !extra.warning().isBlank()) {
                    warnings.add(extra.warning());
                } else {
                    buyersEnriched++;
                }
            }

            String mobileNormalized = firstNonBlank(
                    tryNormalizeMobile(extra.mobile()),
                    tryNormalizeMobile(seed.mobile()));
            String mobileHash = mobileNormalized == null ? null : mobileSupport.hash(mobileNormalized);
            String mobileMasked = mobileNormalized == null ? null : MobileSupport.mask(mobileNormalized);
            String wxOpenIdHash = hashWechatOpenId(extra.weixinOpenId());
            String wxUnionIdHash = hashWechatUnionId(extra.weixinUnionId());
            if (wxOpenIdHash != null) {
                openIdBound++;
            }
            if (wxUnionIdHash != null) {
                unionIdBound++;
            }

            Long linkedCustomerId = resolveLinkedCustomerId(user.tenantId(), shopId, yzOpenId, mobileHash);
            if (linkedCustomerId != null) {
                linkedCustomerCount++;
            }

            String displayName = resolveSafeBuyerName(extra.name(), extra.nickname(), seed.nickname(), seed.receiverName());
            String genderHint = extra.genderHint() == null || extra.genderHint().isBlank() ? "UNKNOWN" : extra.genderHint();

            repository.upsertProfile(new YouzanAnnualProfile(
                    null,
                    user.tenantId(),
                    shopId,
                    yzOpenId,
                    linkedCustomerId,
                    displayName,
                    genderHint,
                    wxOpenIdHash,
                    wxUnionIdHash,
                    mobileHash,
                    mobileMasked,
                    0,
                    0L,
                    null,
                    null,
                    now,
                    null,
                    null
            ));
            repository.refreshProfileStats(user.tenantId(), shopId, yzOpenId, now);
        }

        YouzanAnnualSnapshotSyncResponse response = new YouzanAnnualSnapshotSyncResponse(
                shopId,
                startDate.toString(),
                endDate.toString(),
                windowsPulled,
                ordersSynced,
                paidOrdersSynced,
                buyers.size(),
                buyersEnriched,
                openIdBound,
                unionIdBound,
                linkedCustomerCount,
                new ArrayList<>(warnings),
                now
        );

        auditService.record(user.tenantId(), user.userId(), "YOUZAN_ANNUAL_SNAPSHOT_SYNC", "YOUZAN_SHOP", shopId,
                null,
                Map.of("startDate", startDate.toString(), "endDate", endDate.toString(),
                        "ordersSynced", ordersSynced, "paidOrdersSynced", paidOrdersSynced,
                        "buyersSynced", buyers.size(), "openIdBoundCount", openIdBound,
                        "unionIdBoundCount", unionIdBound, "linkedCustomerCount", linkedCustomerCount),
                "拉取有赞历史订单只读快照与买家身份镜像");
        String compactResourceId = windowsPulled + "," + ordersSynced + "," + paidOrdersSynced + ","
                + buyers.size() + "," + buyersEnriched + "," + openIdBound + "," + unionIdBound + ","
                + linkedCustomerCount;
        idempotencyService.recordSuccess(user.tenantId(), SYNC_SCOPE, idempotencyKey, "YOUZAN_ANNUAL_SYNC",
                compactResourceId, payloadHash);
        return response;
    }

    @Transactional(readOnly = true)
    public YouzanAnnualSnapshotSummaryResponse summary(CurrentUser user, String shopId) {
        requireEmployee(user);
        String cleanShop = shopId == null || shopId.isBlank() ? null : shopId.trim();
        YouzanAnnualSnapshotRepository.SnapshotSummaryRow row = repository.summarizeSnapshots(
                user.tenantId(), cleanShop);
        String entryUrl = properties.publicOrigin() + "/h5/annual-reports/entry";
        if (row == null) {
            return new YouzanAnnualSnapshotSummaryResponse(0, 0, 0, 0, 0, 0, 0, 0, 0, null,
                    entryUrl, properties.isWechatMpConfigured(), properties.isSmsEnabled());
        }
        return new YouzanAnnualSnapshotSummaryResponse(
                row.shopCount(),
                row.profileCount(),
                row.linkedCustomerCount(),
                row.openIdBoundCount(),
                row.unionIdBoundCount(),
                row.mobileBoundCount(),
                row.paidOrderCount(),
                row.totalPaymentFen(),
                row.unifiedVisitSessions(),
                row.lastSyncedAt(),
                entryUrl,
                properties.isWechatMpConfigured(),
                properties.isSmsEnabled()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<YouzanAnnualProfileItemResponse> listProfiles(CurrentUser user, String shopId,
                                                                      String keyword, int page, int size) {
        requireEmployee(user);
        String cleanShop = shopId == null || shopId.isBlank() ? null : shopId.trim();
        String cleanKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        int offset = (page - 1) * size;
        List<YouzanAnnualProfileItemResponse> items = repository.searchProfiles(
                        user.tenantId(), cleanShop, cleanKeyword, offset, size).stream()
                .map(p -> new YouzanAnnualProfileItemResponse(
                        p.id(),
                        p.shopId(),
                        maskExternalId(p.yzOpenId()),
                        p.customerId(),
                        p.displayName(),
                        p.genderHint(),
                        p.mobileMasked(),
                        p.weixinOpenIdHash() != null,
                        p.weixinUnionIdHash() != null,
                        p.orderCount(),
                        p.totalPaymentFen(),
                        p.firstOrderAt(),
                        p.lastOrderAt(),
                        p.syncedAt()))
                .toList();
        long total = repository.countProfiles(user.tenantId(), cleanShop, cleanKeyword);
        return new PageResponse<>(items, total, page, size);
    }

    /**
     * 基于有赞历史订单只读快照实时构建脱敏年终 H5 视图（未绑定 CRM 客户或未建 SKU 映射也可完整展示）。
     */
    @Transactional(readOnly = true)
    public PublicAnnualReportResponse buildPublicReportFromYouzanProfile(long tenantId,
                                                                         YouzanAnnualProfile profile,
                                                                         int reportYear) {
        Instant now = Instant.now();
        Instant yearStart = LocalDate.of(reportYear, 1, 1).atStartOfDay(SHANGHAI).toInstant();
        Instant yearEnd = LocalDate.of(reportYear + 1, 1, 1).atStartOfDay(SHANGHAI).toInstant();

        List<YouzanAnnualOrderSnapshot> orders = repository.findPaidOrdersByBuyerYear(
                tenantId, profile.shopId(), profile.yzOpenId(), yearStart, yearEnd);

        Instant firstMeet = earliestOf(profile.firstOrderAt(),
                orders.isEmpty() ? null : firstNonNull(orders.getFirst().paidAt(), orders.getFirst().orderedAt(), now),
                now);
        LocalDate firstMeetLocal = firstMeet.atZone(SHANGHAI).toLocalDate();
        LocalDate todayLocal = now.atZone(SHANGHAI).toLocalDate();
        long daysTogether = Math.max(1L, ChronoUnit.DAYS.between(firstMeetLocal, todayLocal) + 1L);

        long netSpendFen = 0L;
        List<PublicAnnualReportResponse.OrderSnapshot> orderSnapshots = new ArrayList<>();
        Map<String, Integer> itemFreq = new HashMap<>();
        Map<String, Integer> categoryFreq = new HashMap<>();
        List<PublicAnnualReportResponse.HourPoint> hourPoints = new ArrayList<>();
        int bestNightScore = -1;
        ZonedDateTime bestZdt = now.atZone(SHANGHAI);
        String bestTitle = "有赞商城年度臻选";

        for (YouzanAnnualOrderSnapshot order : orders) {
            long net = Math.max(0L, order.paymentFen() - order.refundedFen());
            netSpendFen += net;
            Instant ts = firstNonNull(order.paidAt(), order.orderedAt(), now);
            ZonedDateTime zdt = ts.atZone(SHANGHAI);
            List<YouzanAnnualPullClient.PulledOrderItem> items = readItems(order.itemsJson());
            String category = inferCategoryFromTitle(order.titleSummary());
            categoryFreq.merge(category, Math.max(1, items.size()), Integer::sum);

            List<PublicAnnualReportResponse.OrderSkuSnapshot> skuSnapshots = new ArrayList<>();
            for (YouzanAnnualPullClient.PulledOrderItem item : items) {
                String label = item.skuProps() == null || item.skuProps().isBlank()
                        ? item.title() : item.title() + " · " + item.skuProps();
                skuSnapshots.add(new PublicAnnualReportResponse.OrderSkuSnapshot(
                        masker.mask(label, 80), item.num() + " 件"));
                itemFreq.merge(masker.mask(item.title(), 60), Math.max(1, item.num()), Integer::sum);
            }
            if (skuSnapshots.isEmpty()) {
                skuSnapshots.add(new PublicAnnualReportResponse.OrderSkuSnapshot(
                        masker.mask(order.titleSummary(), 80), "1 件"));
                itemFreq.merge(masker.mask(order.titleSummary(), 60), 1, Integer::sum);
            }

            String statusText = "TRADE_SUCCESS".equalsIgnoreCase(order.orderStatus()) ? "已完成" : "已支付";
            orderSnapshots.add(new PublicAnnualReportResponse.OrderSnapshot(
                    maskOrderNo(order.tid()),
                    zdt.format(DATE_FMT),
                    zdt.format(MD_FMT),
                    zdt.format(TIME_FMT),
                    seasonOf(zdt.getMonthValue()),
                    masker.mask(order.titleSummary(), 80),
                    category,
                    net,
                    statusText,
                    false,
                    "有赞官方商城",
                    skuSnapshots
            ));

            double hourDec = Math.round((zdt.getHour() + zdt.getMinute() / 60.0) * 100.0) / 100.0;
            hourPoints.add(new PublicAnnualReportResponse.HourPoint(hourDec, 0.72, false, zdt.format(TIME_FMT) + " 有赞下单"));
            int score = nightPriorityScore(zdt.getHour(), zdt.getMinute());
            if (score > bestNightScore) {
                bestNightScore = score;
                bestZdt = zdt;
                bestTitle = masker.mask(order.titleSummary(), 80);
            }
        }

        double bestHour = Math.round((bestZdt.getHour() + bestZdt.getMinute() / 60.0) * 100.0) / 100.0;
        List<PublicAnnualReportResponse.HourPoint> finalPoints = new ArrayList<>();
        boolean marked = false;
        for (PublicAnnualReportResponse.HourPoint pt : hourPoints) {
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

        List<Map.Entry<String, Integer>> sortedItems = itemFreq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
        String topProject = sortedItems.isEmpty() ? "鲸选美学甄选好物" : sortedItems.getFirst().getKey();
        List<PublicAnnualReportResponse.TopProjectItem> top3 = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sortedItems.size()); i++) {
            Map.Entry<String, Integer> e = sortedItems.get(i);
            top3.add(new PublicAnnualReportResponse.TopProjectItem(
                    String.format(Locale.ROOT, "%02d", i + 1), e.getKey(), e.getValue() + " 件"));
        }
        if (top3.isEmpty()) {
            top3.add(new PublicAnnualReportResponse.TopProjectItem("01", topProject, "期待开启"));
        }

        int maxCat = categoryFreq.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        List<PublicAnnualReportResponse.DimensionItem> dimensions = categoryFreq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(4)
                .map(e -> new PublicAnnualReportResponse.DimensionItem(
                        e.getKey(),
                        Math.min(96, Math.max(38, (int) Math.round(e.getValue() * 92.0 / maxCat)))))
                .toList();
        if (dimensions.isEmpty()) {
            dimensions = List.of(
                    new PublicAnnualReportResponse.DimensionItem("甄选美学", 78),
                    new PublicAnnualReportResponse.DimensionItem("日常维养", 66)
            );
        }

        List<String> tags = new ArrayList<>();
        dimensions.forEach(d -> tags.add(d.name()));
        if (tags.size() < 3) {
            tags.add("有赞老友");
        }

        String tierCode = netSpendFen >= 2_000_000L ? "S" : netSpendFen >= 500_000L ? "A" : "B";
        String tierLabel = "S".equals(tierCode) ? "臻选 S 级缪斯" : "A".equals(tierCode) ? "鎏金 A 级尊享" : "鲸选有赞挚友";
        String butlerName = "鲸选美学专属顾问";
        AnnualReportCopyEngine.ToneDecision tone = copyEngine.resolveTone(
                "AUTO", profile.genderHint(), List.of(), tierCode, daysTogether, topProject);
        PublicAnnualReportResponse.Copywriting copy = copyEngine.generate(new AnnualReportCopyEngine.CopyInput(
                reportYear,
                tone.toneCode(),
                profile.genderHint(),
                profile.displayName(),
                butlerName,
                firstMeetLocal.format(DATE_FMT),
                daysTogether,
                orders.size(),
                0,
                0,
                topProject,
                !orders.isEmpty(),
                bestZdt.format(DATE_FMT),
                bestZdt.format(TIME_FMT),
                bestTitle,
                null
        ));

        PublicAnnualReportResponse.VaultSnapshot vault = new PublicAnnualReportResponse.VaultSnapshot(
                0,
                "以专属管家核对为准",
                List.of(new PublicAnnualReportResponse.VaultItem(
                        "有赞历史购买轨迹已同步",
                        "如需查询剩余院线次卡或预约到院，可点击下方一键联系专属顾问",
                        orders.size() + " 笔记录"))
        );

        return new PublicAnnualReportResponse(
                reportYear,
                "YZ · " + maskExternalId(profile.yzOpenId()),
                profile.displayName(),
                tierLabel,
                "臻选" + tierCode,
                butlerName,
                firstMeetLocal.format(DATE_FMT),
                daysTogether,
                orders.size(),
                netSpendFen,
                Math.max(1, categoryFreq.size()),
                0,
                0,
                0,
                tone.toneCode(),
                tone.toneLabel(),
                tone.reason(),
                copy,
                new PublicAnnualReportResponse.MidnightSnapshot(
                        bestZdt.format(TIME_FMT),
                        bestZdt.format(DATE_FMT),
                        bestTitle,
                        "有赞官方商城下单时刻（Asia/Shanghai）",
                        computePeakLabel(finalPoints),
                        finalPoints.stream().limit(12).toList()
                ),
                new PublicAnnualReportResponse.FavoriteSnapshot(
                        topProject,
                        orders.size() >= 3 ? "有赞高频回购 · 稳居年度核心清单" : "年度有赞臻选记忆",
                        tags,
                        dimensions,
                        top3
                ),
                vault,
                orderSnapshots
        );
    }

    public String hashWechatOpenId(String openId) {
        String clean = YouzanAnnualPullClient.stripCiphertext(openId);
        if (clean == null) {
            return null;
        }
        return blindIndex.hash(WX_OPENID_HASH_PREFIX + clean);
    }

    public String hashWechatUnionId(String unionId) {
        String clean = YouzanAnnualPullClient.stripCiphertext(unionId);
        if (clean == null) {
            return null;
        }
        return blindIndex.hash(WX_UNIONID_HASH_PREFIX + clean);
    }

    private Long resolveLinkedCustomerId(long tenantId, String shopId, String yzOpenId, String mobileHash) {
        Optional<Long> direct = repository.findLinkedCustomerIdByYzOpenId(tenantId, shopId, yzOpenId);
        if (direct.isPresent()) {
            return direct.orElseThrow();
        }
        if (mobileHash != null) {
            List<Long> matches = repository.findCustomerIdsByMobileHash(tenantId, mobileHash);
            if (matches.size() == 1) {
                return matches.getFirst();
            }
        }
        return null;
    }

    private BuyerSeed mergeSeed(BuyerSeed existing, YouzanAnnualPullClient.PulledOrder pulled) {
        String nick = existing == null ? YouzanAnnualPullClient.stripCiphertext(pulled.buyerNickname())
                : firstPlainText(existing.nickname(), pulled.buyerNickname());
        String mobile = existing == null ? YouzanAnnualPullClient.stripCiphertext(pulled.buyerMobile())
                : firstPlainText(existing.mobile(), pulled.buyerMobile());
        String receiver = existing == null ? YouzanAnnualPullClient.stripCiphertext(pulled.receiverName())
                : firstPlainText(existing.receiverName(), pulled.receiverName());
        Integer fansType = existing == null ? null : existing.fansType();
        Long fansId = existing == null ? null : existing.fansId();
        boolean existingHasFans = fansType != null && fansType > 0 && fansId != null && fansId > 0;
        boolean pulledHasFans = pulled.buyerFansType() != null && pulled.buyerFansType() > 0
                && pulled.buyerFansId() != null && pulled.buyerFansId() > 0;
        if (!existingHasFans && pulledHasFans) {
            fansType = pulled.buyerFansType();
            fansId = pulled.buyerFansId();
        }
        return new BuyerSeed(nick, mobile, receiver, fansType, fansId);
    }

    private String resolveSafeBuyerName(String scrmName, String userNick, String orderNick, String receiverName) {
        String candidate = firstMeaningfulName(scrmName, userNick, orderNick, receiverName);
        if (candidate == null) {
            return "鲸选贵宾";
        }
        return masker.mask(candidate, 48);
    }

    private String firstMeaningfulName(String... values) {
        for (String val : values) {
            String clean = YouzanAnnualPullClient.stripCiphertext(val);
            if (clean != null && !"微信用户".equals(clean) && !"有赞用户".equals(clean)) {
                return clean;
            }
        }
        return null;
    }

    private String tryNormalizeMobile(String raw) {
        String clean = YouzanAnnualPullClient.stripCiphertext(raw);
        if (clean == null) {
            return null;
        }
        try {
            return mobileSupport.normalize(clean);
        } catch (BusinessException ex) {
            return null;
        }
    }

    private String inferCategoryFromTitle(String title) {
        if (title == null) {
            return "臻选美学";
        }
        if (title.contains("水光") || title.contains("补水") || title.contains("玻尿酸")) {
            return "深层水光";
        }
        if (title.contains("光子") || title.contains("超光子") || title.contains("皮秒") || title.contains("激光")
                || title.contains("黄金微针")) {
            return "光电焕亮";
        }
        if (title.contains("热玛吉") || title.contains("超声") || title.contains("抗衰") || title.contains("紧致")
                || title.contains("提拉")) {
            return "轮廓紧致";
        }
        if (title.contains("修护") || title.contains("舒敏") || title.contains("面膜") || title.contains("护理")) {
            return "敏肌修护";
        }
        return "臻选美学";
    }

    private void requireEmployee(CurrentUser user) {
        if (user == null || !"EMPLOYEE".equals(user.userType())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅内部员工可管理有赞年度快照");
        }
    }

    private YouzanAnnualSnapshotSyncResponse parseReplay(String compact, String shopId,
                                                         LocalDate startDate, LocalDate endDate) {
        if (compact != null && !compact.isBlank()) {
            String[] parts = compact.split(",");
            if (parts.length == 8) {
                try {
                    return new YouzanAnnualSnapshotSyncResponse(
                            shopId,
                            startDate.toString(),
                            endDate.toString(),
                            Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]),
                            Integer.parseInt(parts[4]),
                            Integer.parseInt(parts[5]),
                            Integer.parseInt(parts[6]),
                            Integer.parseInt(parts[7]),
                            List.of(),
                            Instant.now()
                    );
                } catch (NumberFormatException ex) {
                    // 兜底返回空重放
                }
            }
        }
        return new YouzanAnnualSnapshotSyncResponse(shopId, startDate.toString(), endDate.toString(),
                0, 0, 0, 0, 0, 0, 0, 0, List.of("幂等重放"), Instant.now());
    }

    private List<YouzanAnnualPullClient.PulledOrderItem> readItems(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<YouzanAnnualPullClient.PulledOrderItem>>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "快照数据序列化失败");
        }
    }

    private int nightPriorityScore(int hour, int minute) {
        int shifted = hour < 4 ? hour + 24 : hour;
        return shifted * 60 + minute;
    }

    private String computePeakLabel(List<PublicAnnualReportResponse.HourPoint> points) {
        if (points.isEmpty()) {
            return "高频活跃时段：全天从容安排";
        }
        long nightCount = points.stream().filter(p -> p.hour() >= 20.0 || p.hour() < 4.0).count();
        long afternoonCount = points.stream().filter(p -> p.hour() >= 12.0 && p.hour() < 20.0).count();
        if (nightCount >= afternoonCount && nightCount > 0) {
            return "高频下单时段：20:00–02:00（占比 " + Math.round(nightCount * 100.0 / points.size()) + "%）";
        }
        if (afternoonCount > 0) {
            return "高频下单时段：12:00–20:00（占比 " + Math.round(afternoonCount * 100.0 / points.size()) + "%）";
        }
        return "高频下单时段：08:00–12:00 晨间选购";
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

    private String maskOrderNo(String raw) {
        if (raw == null || raw.isBlank()) {
            return "YZ****0000";
        }
        String clean = raw.trim();
        if (clean.length() <= 8) {
            return clean.substring(0, Math.min(2, clean.length())) + "****";
        }
        return clean.substring(0, 6) + "****" + clean.substring(clean.length() - 4);
    }

    private String maskExternalId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "****";
        }
        String clean = raw.trim();
        if (clean.length() <= 6) {
            return clean.substring(0, 2) + "***";
        }
        return clean.substring(0, 3) + "***" + clean.substring(clean.length() - 3);
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
        for (Instant v : values) {
            if (v != null) {
                return v;
            }
        }
        return Instant.now();
    }

    private String firstPlainText(String... values) {
        for (String v : values) {
            String clean = YouzanAnnualPullClient.stripCiphertext(v);
            if (clean != null) {
                return clean;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private record BuyerSeed(String nickname, String mobile, String receiverName,
                             Integer fansType, Long fansId) {
    }
}
