package com.whale.crm.integration.youzan.customer.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerExternalImportService;
import com.whale.crm.customer.application.MobileSupport;
import com.whale.crm.integration.youzan.application.YouzanRiskLinkOperations;
import com.whale.crm.integration.youzan.customer.adapter.YouzanCustomerPullClient;
import com.whale.crm.integration.youzan.customer.adapter.YouzanCustomerPullClientRouter;
import com.whale.crm.integration.youzan.customer.adapter.YouzanCustomerWindowTooLargeException;
import com.whale.crm.integration.youzan.customer.api.dto.YouzanCustomerCursorSummary;
import com.whale.crm.integration.youzan.customer.api.dto.YouzanCustomerPullRequest;
import com.whale.crm.integration.youzan.customer.api.dto.YouzanCustomerSyncItemResponse;
import com.whale.crm.integration.youzan.customer.api.dto.YouzanCustomerSyncRunResponse;
import com.whale.crm.integration.youzan.customer.api.dto.YouzanCustomerSyncStatusResponse;
import com.whale.crm.integration.youzan.customer.domain.YouzanCustomerSyncCursor;
import com.whale.crm.integration.youzan.customer.domain.YouzanCustomerSyncItem;
import com.whale.crm.integration.youzan.customer.domain.YouzanCustomerSyncRepository;
import com.whale.crm.integration.youzan.customer.domain.YouzanCustomerSyncRun;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 有赞客户受控拉取与 CRM 正式建档应用服务。
 * 核心保障：
 * 1. 固定底层分页大小（SCRM_LIST=20、ORDER_BUYERS=50）+ 持久化断点游标，跨批次切换条数不重不漏；
 * 2. 强幂等防重（crm_youzan_customer_link + crm_customer_identity）与手机号冲突不覆盖老客户；
 * 3. running_shop_key 并发互斥锁在异常路径可靠释放；
 * 4. 全链路脱敏，不落库/不回传/不审计明文手机号与明文微信 OpenID。
 */
@Service
public class YouzanCustomerSyncService {

    public static final String SOURCE_MODE_SCRM_LIST = "SCRM_LIST";
    public static final String SOURCE_MODE_ORDER_BUYERS = "ORDER_BUYERS";
    public static final String PULL_MODE_BATCH = "BATCH";
    public static final String PULL_MODE_ALL = "ALL";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";

    public static final String IDENTITY_TYPE_YOUZAN = "YOUZAN";
    public static final String LINK_TYPE_STRONG = "STRONG";
    public static final String LINK_CONFIDENCE_HIGH = "HIGH";
    public static final String LINK_REASON_AUTO = "有赞客户同步自动建档关联";

    static final int SCRM_PAGE_SIZE = 20;
    static final int ORDER_BUYERS_PAGE_SIZE = 50;
    static final int DEFAULT_BATCH_LIMIT = 20;
    static final int DEFAULT_ALL_LIMIT = 200;
    static final int MAX_LIMIT = 500;
    static final int MAX_ALL_PAGES_PER_RUN = 30;
    static final int MAX_BATCH_PAGES_CAP = 60;
    static final int MAX_WINDOW_SPLITS_PER_RUN = 40;
    static final int INLINE_ITEMS_LIMIT = 50;
    static final Duration ORDER_BUYERS_MAX_WINDOW = Duration.ofDays(89);
    static final Duration ORDER_BUYERS_DEFAULT_LOOKBACK = Duration.ofDays(365);
    static final Duration INCREMENTAL_OVERLAP = Duration.ofSeconds(60);
    static final Duration RUN_LEASE_DURATION = Duration.ofMinutes(10);
    static final Instant SCRM_FALLBACK_START = Instant.parse("2015-01-01T00:00:00Z");

    private static final Logger LOG = LoggerFactory.getLogger(YouzanCustomerSyncService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String IDEMPOTENCY_SCOPE_PULL = "youzan.customer.sync.pull";
    private static final String AUDIT_TARGET_TYPE = "YOUZAN_CUSTOMER_SYNC";
    private static final Pattern CN_MOBILE_PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");
    private static final Set<String> VALID_OUTCOMES = Set.of(
            CustomerExternalImportService.OUTCOME_CREATED,
            CustomerExternalImportService.OUTCOME_SKIPPED_EXISTING,
            CustomerExternalImportService.OUTCOME_MOBILE_CONFLICT,
            CustomerExternalImportService.OUTCOME_FAILED
    );
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };

    private final YouzanCustomerPullClientRouter pullClientProvider;
    private final YouzanCustomerSyncRepository syncRepository;
    private final YouzanRepository youzanRepository;
    private final CustomerExternalImportService importService;
    private final UserRepository userRepository;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final YouzanRiskLinkOperations riskLinkOperations;
    private final Clock clock;

    @Autowired
    public YouzanCustomerSyncService(YouzanCustomerPullClientRouter pullClientProvider,
                                     YouzanCustomerSyncRepository syncRepository,
                                     YouzanRepository youzanRepository,
                                     CustomerExternalImportService importService,
                                     UserRepository userRepository,
                                     IdempotencyService idempotencyService,
                                     AuditService auditService,
                                     ObjectMapper objectMapper,
                                     ObjectProvider<YouzanRiskLinkOperations> riskLinkOperationsProvider) {
        this(pullClientProvider, syncRepository, youzanRepository, importService, userRepository,
                idempotencyService, auditService, objectMapper,
                riskLinkOperationsProvider == null ? YouzanRiskLinkOperations.NOOP
                        : riskLinkOperationsProvider.getIfAvailable(() -> YouzanRiskLinkOperations.NOOP),
                Clock.system(BUSINESS_ZONE));
    }

    public YouzanCustomerSyncService(YouzanCustomerPullClientRouter pullClientProvider,
                                     YouzanCustomerSyncRepository syncRepository,
                                     YouzanRepository youzanRepository,
                                     CustomerExternalImportService importService,
                                     UserRepository userRepository,
                                     IdempotencyService idempotencyService,
                                     AuditService auditService,
                                     ObjectMapper objectMapper,
                                     YouzanRiskLinkOperations riskLinkOperations,
                                     Clock clock) {
        this.pullClientProvider = pullClientProvider;
        this.syncRepository = syncRepository;
        this.youzanRepository = youzanRepository;
        this.importService = importService;
        this.userRepository = userRepository;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.riskLinkOperations = riskLinkOperations != null ? riskLinkOperations : YouzanRiskLinkOperations.NOOP;
        this.clock = clock != null ? clock : Clock.system(BUSINESS_ZONE);
    }

    @Transactional(readOnly = true)
    public YouzanCustomerSyncStatusResponse getStatus(CurrentUser operator, String rawShopId) {
        requireEmployee(operator);
        long tenantId = operator.tenantId();
        String shopId = normalizeShopId(rawShopId);
        return buildStatusResponse(tenantId, shopId);
    }

    @Transactional
    public YouzanCustomerSyncStatusResponse resetCursor(CurrentUser operator, String rawShopId, String rawSourceMode) {
        requireEmployee(operator);
        long tenantId = operator.tenantId();
        String shopId = normalizeShopId(rawShopId);
        String sourceMode = normalizeSourceMode(rawSourceMode);
        Optional<YouzanCustomerSyncCursor> beforeOpt = syncRepository.findCursor(tenantId, shopId, sourceMode);
        syncRepository.resetCursor(tenantId, shopId, sourceMode);

        Map<String, Object> beforeSnapshot = beforeOpt.map(this::cursorAuditSnapshot).orElse(null);
        Map<String, Object> afterSnapshot = new LinkedHashMap<>();
        afterSnapshot.put("shopId", shopId);
        afterSnapshot.put("sourceMode", sourceMode);
        afterSnapshot.put("reset", true);
        auditService.record(tenantId, operator.userId(), AuditService.ACTION_YOUZAN_CUSTOMER_CURSOR_RESET,
                AUDIT_TARGET_TYPE, shopId + ":" + sourceMode, beforeSnapshot, afterSnapshot,
                "手动重置有赞客户同步断点游标");

        return buildStatusResponse(tenantId, shopId);
    }

    @Transactional(readOnly = true)
    public PageResponse<YouzanCustomerSyncRunResponse> listRuns(CurrentUser operator, String rawShopId,
                                                                String rawSourceMode, int page, int size) {
        requireEmployee(operator);
        long tenantId = operator.tenantId();
        String shopId = normalizeShopId(rawShopId);
        String sourceMode = isBlank(rawSourceMode) ? null : normalizeSourceMode(rawSourceMode);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int offset = (safePage - 1) * safeSize;

        List<YouzanCustomerSyncRun> runs = syncRepository.listRuns(tenantId, shopId, sourceMode, offset, safeSize);
        long total = syncRepository.countRuns(tenantId, shopId, sourceMode);
        Map<Long, String> ownerNames = resolveOwnerNames(tenantId, runs);
        List<YouzanCustomerSyncRunResponse> items = runs.stream()
                .map(run -> toRunResponse(run, ownerNames.get(run.ownerStaffId()), null))
                .toList();
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public PageResponse<YouzanCustomerSyncItemResponse> listRunItems(CurrentUser operator, long runId,
                                                                     String rawOutcome, int page, int size) {
        requireEmployee(operator);
        long tenantId = operator.tenantId();
        syncRepository.findRun(tenantId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "有赞客户同步批次不存在"));
        String outcome = normalizeOptionalOutcome(rawOutcome);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int offset = (safePage - 1) * safeSize;

        List<YouzanCustomerSyncItemResponse> items = syncRepository
                .listItems(tenantId, runId, outcome, offset, safeSize)
                .stream()
                .map(this::toItemResponse)
                .toList();
        long total = syncRepository.countItems(tenantId, runId, outcome);
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    /**
     * 执行一次有赞客户拉取建档批次（非整方法大事务：并发锁与单条建档各自独立提交，避免外部 HTTP 占住连接）。
     */
    public YouzanCustomerSyncRunResponse pull(CurrentUser operator, YouzanCustomerPullRequest request,
                                              String rawIdempotencyKey) {
        requireEmployee(operator);
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "拉取请求体不能为空");
        }
        String idempotencyKey = requireIdempotencyKey(rawIdempotencyKey);
        long tenantId = operator.tenantId();
        String shopId = normalizeShopId(request.shopId());
        String sourceMode = normalizeSourceMode(request.sourceMode());
        String pullMode = normalizePullMode(request.pullMode());
        int targetCount = resolveTargetCount(pullMode, request.limit());
        Integer requestedLimit = PULL_MODE_BATCH.equals(pullMode) ? Integer.valueOf(targetCount) : request.limit();
        if (request.ownerStaffId() == null || request.ownerStaffId() <= 0L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标专属管家不能为空");
        }
        LocalDate startDate = parseOptionalDate(request.startDate(), "开始日期");
        LocalDate endDate = parseOptionalDate(request.endDate(), "结束日期");
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        boolean enrichProfile = request.enrichProfile() == null || request.enrichProfile();
        boolean resetCursor = Boolean.TRUE.equals(request.resetCursor());

        String canonicalPayload = buildCanonicalPullPayload(shopId, sourceMode, pullMode, request.limit(),
                request.ownerStaffId(), request.sourceCode(), request.channelCode(),
                startDate, endDate, enrichProfile, resetCursor);

        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                tenantId, IDEMPOTENCY_SCOPE_PULL, operator.userId(), idempotencyKey, canonicalPayload);
        if (replay.isPresent()) {
            long existingRunId = parseRunId(replay.get().getResourceId());
            return loadRunResponseWithItems(tenantId, existingRunId);
        }

        CustomerExternalImportService.ValidatedImportTarget target = importService.validateImportTarget(
                tenantId, operator, request.ownerStaffId(), request.sourceCode(), request.channelCode());
        YouzanCustomerPullClient client = pullClientProvider.resolve(tenantId, shopId);

        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant leaseUntil = startedAt.plus(RUN_LEASE_DURATION);
        long runId = syncRepository.tryStartRun(tenantId, shopId, sourceMode, pullMode, requestedLimit,
                target.ownerStaffId(), client.name(), operator.userId(), startedAt, leaseUntil);
        if (runId <= 0L) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前店铺该模式已有客户拉取任务执行中，请稍后重试");
        }

        int pageSize = SOURCE_MODE_SCRM_LIST.equals(sourceMode) ? SCRM_PAGE_SIZE : ORDER_BUYERS_PAGE_SIZE;
        int maxPages = resolveMaxPages(pullMode, targetCount, pageSize);

        YouzanCustomerCursorState state = null;
        Instant anchorEnd = null;
        Instant existingLastAnchorAt = null;
        int fetchedCount = 0;
        int createdCount = 0;
        int skippedExistingCount = 0;
        int mobileConflictCount = 0;
        int failedCount = 0;
        int actionableCount = 0;
        boolean hasMore = true;
        boolean finalized = false;
        Set<String> warnings = new LinkedHashSet<>();
        Set<String> seenInRun = new HashSet<>();
        Map<String, Optional<String>> tagCodeCache = new HashMap<>();
        List<YouzanCustomerSyncItemResponse> inlineFallbackItems = new ArrayList<>();

        try {
            YouzanCustomerSyncCursor existingCursor = null;
            if (resetCursor) {
                syncRepository.resetCursor(tenantId, shopId, sourceMode);
            } else {
                existingCursor = syncRepository.findCursor(tenantId, shopId, sourceMode).orElse(null);
                existingLastAnchorAt = existingCursor == null ? null : existingCursor.lastAnchorAt();
            }

            state = initOrResumeCursor(sourceMode, existingCursor, startDate, endDate, startedAt);
            anchorEnd = state.anchorEnd();

            int pagesScanned = 0;
            int splitCount = 0;
            while (pagesScanned < maxPages && actionableCount < targetCount && state != null) {
                YouzanCustomerPullClient.YouzanCustomerPage page;
                try {
                    page = fetchPage(client, tenantId, shopId, sourceMode, state, pageSize);
                } catch (YouzanCustomerWindowTooLargeException ex) {
                    if (++splitCount > MAX_WINDOW_SPLITS_PER_RUN) {
                        throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                                "有赞客户时间窗切分次数超限，请缩小日期范围后重试");
                    }
                    state = bisectWindow(state);
                    warnings.add("部分时间窗客户数量超平台翻页上限，已自动二分切窗分段拉取");
                    continue;
                }
                pagesScanned++;
                for (String w : page.warnings()) {
                    if (!isBlank(w)) {
                        warnings.add(sanitizeText(w));
                    }
                }

                List<YouzanCustomerPullClient.YouzanPulledCustomer> items = page.items();
                int startIdx = Math.max(0, state.itemOffset());
                if (items.isEmpty() || startIdx >= items.size()) {
                    boolean effectivePageHasMore = !items.isEmpty() && page.hasMore();
                    state = advancePastPage(state, effectivePageHasMore, sourceMode);
                    if (state == null) {
                        hasMore = false;
                        break;
                    }
                    continue;
                }

                boolean hitTarget = false;
                for (int i = startIdx; i < items.size(); i++) {
                    YouzanCustomerPullClient.YouzanPulledCustomer pulled = items.get(i);
                    ItemProcessResult outcome = processSingleCustomer(
                            tenantId, operator, target, client, shopId, sourceMode, enrichProfile,
                            runId, pulled, seenInRun, tagCodeCache, warnings, inlineFallbackItems);
                    if (outcome.recorded()) {
                        fetchedCount++;
                        switch (outcome.outcome()) {
                            case CustomerExternalImportService.OUTCOME_CREATED -> {
                                createdCount++;
                                actionableCount++;
                            }
                            case CustomerExternalImportService.OUTCOME_SKIPPED_EXISTING -> skippedExistingCount++;
                            case CustomerExternalImportService.OUTCOME_MOBILE_CONFLICT -> {
                                mobileConflictCount++;
                                actionableCount++;
                            }
                            default -> {
                                failedCount++;
                                actionableCount++;
                            }
                        }
                    }

                    int nextOffset = i + 1;
                    if (nextOffset < items.size()) {
                        state = new YouzanCustomerCursorState(
                                state.anchorStart(), state.anchorEnd(),
                                state.windowStart(), state.windowEnd(),
                                state.pageNo(), nextOffset);
                    } else {
                        state = advancePastPage(state, page.hasMore(), sourceMode);
                        if (state == null) {
                            hasMore = false;
                            break;
                        }
                    }

                    if (actionableCount >= targetCount) {
                        hasMore = true;
                        hitTarget = true;
                        break;
                    }
                }

                if (state == null || hitTarget) {
                    break;
                }
            }

            if (state != null && actionableCount < targetCount && pagesScanned >= maxPages) {
                warnings.add("本批已达单次最大翻页保护上限（" + maxPages + "页），已保存断点游标，可继续拉取");
            }
            hasMore = state != null;

            Instant finishedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            String cursorAfterJson = state != null ? toJson(state) : null;
            Instant completedAnchorAt = hasMore ? null : maxInstant(existingLastAnchorAt, anchorEnd);

            // 注意：upsertCursor SQL 累加 total_imported_count，因此此处严格传本次增量 createdCount
            syncRepository.upsertCursor(new YouzanCustomerSyncCursor(
                    null, tenantId, shopId, sourceMode, cursorAfterJson, hasMore,
                    completedAnchorAt, finishedAt, createdCount, null, null, 0L));

            String finalStatus = (failedCount > 0 || mobileConflictCount > 0) ? STATUS_PARTIAL : STATUS_SUCCEEDED;
            String warningsJson = warnings.isEmpty() ? null : toJson(List.copyOf(warnings));
            syncRepository.finishRun(tenantId, runId, finalStatus, fetchedCount, createdCount,
                    skippedExistingCount, mobileConflictCount, failedCount, hasMore,
                    cursorAfterJson, warningsJson, null, finishedAt);
            finalized = true;

            idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_PULL, operator.userId(),
                    idempotencyKey, AUDIT_TARGET_TYPE, Long.toString(runId), canonicalPayload);

            recordPullAudit(tenantId, operator.userId(), runId, shopId, sourceMode, pullMode,
                    requestedLimit, target.ownerStaffId(), client.name(), finalStatus,
                    fetchedCount, createdCount, skippedExistingCount, mobileConflictCount, failedCount,
                    hasMore, resetCursor, warnings.size());

            List<YouzanCustomerSyncItemResponse> persistedItems = syncRepository
                    .listItems(tenantId, runId, null, 0, INLINE_ITEMS_LIMIT)
                    .stream()
                    .map(this::toItemResponse)
                    .toList();
            List<YouzanCustomerSyncItemResponse> responseItems = persistedItems.isEmpty() && !inlineFallbackItems.isEmpty()
                    ? List.copyOf(inlineFallbackItems) : persistedItems;

            return new YouzanCustomerSyncRunResponse(
                    runId, shopId, sourceMode, pullMode, requestedLimit,
                    target.ownerStaffId(), target.ownerDisplayName(), client.name(), finalStatus,
                    fetchedCount, createdCount, skippedExistingCount, mobileConflictCount, failedCount,
                    hasMore, List.copyOf(warnings), null, startedAt, finishedAt, responseItems);
        } catch (RuntimeException ex) {
            String errorMasked = sanitizeText(ex.getMessage());
            if (isBlank(errorMasked)) {
                errorMasked = "有赞客户拉取执行失败";
            }
            if (!finalized) {
                Instant finishedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                String cursorAfterJson = state != null ? toJson(state) : null;
                if (fetchedCount > 0 && state != null) {
                    try {
                        syncRepository.upsertCursor(new YouzanCustomerSyncCursor(
                                null, tenantId, shopId, sourceMode, cursorAfterJson, true,
                                null, finishedAt, createdCount, null, null, 0L));
                    } catch (RuntimeException cursorEx) {
                        LOG.warn("有赞客户同步异常后保存断点游标失败 runId={}: {}", runId, sanitizeText(cursorEx.getMessage()));
                    }
                }
                try {
                    String warningsJson = warnings.isEmpty() ? null : toJson(List.copyOf(warnings));
                    syncRepository.finishRun(tenantId, runId, STATUS_FAILED, fetchedCount, createdCount,
                            skippedExistingCount, mobileConflictCount, failedCount, true,
                            cursorAfterJson, warningsJson, truncate(errorMasked, 1000), finishedAt);
                    finalized = true;
                } catch (RuntimeException finishEx) {
                    LOG.error("有赞客户同步释放并发锁失败 runId={}: {}", runId, sanitizeText(finishEx.getMessage()));
                }
            }
            throw ex;
        } finally {
            if (!finalized) {
                try {
                    syncRepository.finishRun(tenantId, runId, STATUS_FAILED, fetchedCount, createdCount,
                            skippedExistingCount, mobileConflictCount, failedCount, true,
                            state != null ? toJson(state) : null, null, "同步任务异常终止，已释放并发锁",
                            clock.instant().truncatedTo(ChronoUnit.MILLIS));
                } catch (RuntimeException finishEx) {
                    LOG.error("有赞客户同步兜底释放并发锁失败 runId={}: {}", runId, sanitizeText(finishEx.getMessage()));
                }
            }
        }
    }

    private ItemProcessResult processSingleCustomer(
            long tenantId, CurrentUser operator, CustomerExternalImportService.ValidatedImportTarget target,
            YouzanCustomerPullClient client, String shopId, String sourceMode, boolean enrichProfile,
            long runId, YouzanCustomerPullClient.YouzanPulledCustomer pulled,
            Set<String> seenInRun, Map<String, Optional<String>> tagCodeCache,
            Set<String> warnings, List<YouzanCustomerSyncItemResponse> inlineFallbackItems) {
        String yzOpenId = pulled == null ? null : trimToNull(pulled.yzOpenId());
        if (yzOpenId == null) {
            String displayMasked = pulled == null ? null : maskDisplayName(pulled.name(), pulled.nickname());
            String mobileMasked = pulled == null ? null : maskRawMobile(pulled.mobile());
            persistItem(tenantId, runId, shopId, "(EMPTY)", CustomerExternalImportService.OUTCOME_FAILED,
                    displayMasked, mobileMasked, null, null, List.of(), List.of(),
                    "有赞客户缺少有效 yz_open_id，已跳过", inlineFallbackItems);
            return new ItemProcessResult(true, CustomerExternalImportService.OUTCOME_FAILED);
        }

        if (!seenInRun.add(yzOpenId)) {
            return new ItemProcessResult(false, null);
        }

        Optional<YouzanCustomerLink> existingLink = youzanRepository.findCustomerLink(tenantId, shopId, yzOpenId);
        if (existingLink.isPresent()) {
            long linkedCustomerId = existingLink.get().customerId();
            Optional<CustomerExternalImportService.ExistingCustomerRef> refOpt = importService.ensureIdentityLinked(
                    tenantId, linkedCustomerId, IDENTITY_TYPE_YOUZAN, shopId, yzOpenId);
            if (refOpt.isPresent()) {
                CustomerExternalImportService.ExistingCustomerRef ref = refOpt.get();
                String detail = "该有赞客户已绑定 CRM 档案 " + ref.customerNo() + "，自动跳过";
                if (enrichProfile) {
                    ResolvedPulledProfile resolved = resolveProfile(tenantId, shopId, sourceMode, true,
                            client, yzOpenId, pulled, warnings);
                    detail = tryBackfillExisting(tenantId, operator, ref, shopId, sourceMode,
                            resolved, tagCodeCache, detail);
                }
                persistItem(tenantId, runId, shopId, yzOpenId, CustomerExternalImportService.OUTCOME_SKIPPED_EXISTING,
                        maskDisplayName(ref.displayName(), null), ref.mobileMasked(),
                        ref.customerId(), ref.customerNo(), List.of(), List.of(),
                        detail, inlineFallbackItems);
                return new ItemProcessResult(true, CustomerExternalImportService.OUTCOME_SKIPPED_EXISTING);
            }
            persistItem(tenantId, runId, shopId, yzOpenId, CustomerExternalImportService.OUTCOME_FAILED,
                    maskDisplayName(pulled.name(), pulled.nickname()), maskRawMobile(pulled.mobile()),
                    linkedCustomerId, null, List.of(), List.of(),
                    "该有赞客户已关联的 CRM 档案已归档或不存在，请人工核查", inlineFallbackItems);
            return new ItemProcessResult(true, CustomerExternalImportService.OUTCOME_FAILED);
        }

        ResolvedPulledProfile resolved = resolveProfile(tenantId, shopId, sourceMode, enrichProfile,
                client, yzOpenId, pulled, warnings);
        List<String> resolvedTagCodes = resolveTagCodesCached(tenantId, operator, resolved.tagNames(), tagCodeCache);

        try {
            CustomerExternalImportService.ExternalCustomerCommand cmd =
                    new CustomerExternalImportService.ExternalCustomerCommand(
                            IDENTITY_TYPE_YOUZAN,
                            shopId,
                            yzOpenId,
                            resolved.name(),
                            resolved.nickname(),
                            resolved.mobile(),
                            resolved.birthday(),
                            pulled.createdAt(),
                            resolved.remark(),
                            "有赞客户拉取建档（shopId=" + shopId + ", mode=" + sourceMode + "）",
                            resolved.cityName(),
                            resolvedTagCodes);
            CustomerExternalImportService.ExternalImportResult result = importService.importCustomer(
                    tenantId, operator, target, cmd);

            if (CustomerExternalImportService.OUTCOME_CREATED.equals(result.outcome())) {
                if (result.crmCustomerId() != null) {
                    ensureYouzanCustomerLink(tenantId, shopId, yzOpenId, result.crmCustomerId(), operator.userId());
                    linkUnresolvedRiskIssues(tenantId, shopId, yzOpenId, result.crmCustomerId());
                }
                persistItem(tenantId, runId, shopId, yzOpenId, CustomerExternalImportService.OUTCOME_CREATED,
                        maskDisplayName(result.displayNameMasked(), null), result.mobileMasked(),
                        result.crmCustomerId(), result.crmCustomerNo(), List.of(), List.of(),
                        sanitizeText(result.detailMasked()), inlineFallbackItems);
                return new ItemProcessResult(true, CustomerExternalImportService.OUTCOME_CREATED);
            }

            if (CustomerExternalImportService.OUTCOME_SKIPPED_EXISTING.equals(result.outcome())) {
                String detail = sanitizeText(result.detailMasked());
                if (result.crmCustomerId() != null) {
                    ensureYouzanCustomerLink(tenantId, shopId, yzOpenId, result.crmCustomerId(), operator.userId());
                    linkUnresolvedRiskIssues(tenantId, shopId, yzOpenId, result.crmCustomerId());
                    if (enrichProfile) {
                        CustomerExternalImportService.ExistingCustomerRef ref =
                                new CustomerExternalImportService.ExistingCustomerRef(
                                        result.crmCustomerId(), result.crmCustomerNo(),
                                        result.displayNameMasked(), result.mobileMasked());
                        detail = tryBackfillExisting(tenantId, operator, ref, shopId, sourceMode,
                                resolved, tagCodeCache, detail);
                    }
                }
                persistItem(tenantId, runId, shopId, yzOpenId, CustomerExternalImportService.OUTCOME_SKIPPED_EXISTING,
                        maskDisplayName(result.displayNameMasked(), null), result.mobileMasked(),
                        result.crmCustomerId(), result.crmCustomerNo(), List.of(), List.of(),
                        detail, inlineFallbackItems);
                return new ItemProcessResult(true, CustomerExternalImportService.OUTCOME_SKIPPED_EXISTING);
            }

            if (CustomerExternalImportService.OUTCOME_MOBILE_CONFLICT.equals(result.outcome())) {
                persistItem(tenantId, runId, shopId, yzOpenId, CustomerExternalImportService.OUTCOME_MOBILE_CONFLICT,
                        maskDisplayName(result.displayNameMasked(), null), result.mobileMasked(),
                        null, null, result.conflictCustomerIds(), result.conflictCustomerNos(),
                        sanitizeText(result.detailMasked()), inlineFallbackItems);
                return new ItemProcessResult(true, CustomerExternalImportService.OUTCOME_MOBILE_CONFLICT);
            }

            persistItem(tenantId, runId, shopId, yzOpenId, CustomerExternalImportService.OUTCOME_FAILED,
                    maskDisplayName(result.displayNameMasked(), null), result.mobileMasked(),
                    null, null, List.of(), List.of(),
                    sanitizeText(result.detailMasked()), inlineFallbackItems);
            return new ItemProcessResult(true, CustomerExternalImportService.OUTCOME_FAILED);
        } catch (RuntimeException ex) {
            String reason = sanitizeText(ex.getMessage());
            if (isBlank(reason)) {
                reason = "客户建档处理失败";
            }
            persistItem(tenantId, runId, shopId, yzOpenId, CustomerExternalImportService.OUTCOME_FAILED,
                    maskDisplayName(resolved.name(), resolved.nickname()), maskRawMobile(resolved.mobile()),
                    null, null, List.of(), List.of(), truncate(reason, 500), inlineFallbackItems);
            return new ItemProcessResult(true, CustomerExternalImportService.OUTCOME_FAILED);
        }
    }

    private ResolvedPulledProfile resolveProfile(long tenantId, String shopId, String sourceMode,
                                                 boolean enrichProfile, YouzanCustomerPullClient client,
                                                 String yzOpenId,
                                                 YouzanCustomerPullClient.YouzanPulledCustomer pulled,
                                                 Set<String> warnings) {
        String name = pulled.name();
        String nickname = pulled.nickname();
        String mobile = pulled.mobile();
        LocalDate birthday = pulled.birthday();
        String remark = pulled.remark();
        String cityName = pulled.cityName();
        Set<String> tagNames = new LinkedHashSet<>(pulled.tagNames() == null ? List.of() : pulled.tagNames());

        boolean needsEnrich = enrichProfile
                && (SOURCE_MODE_ORDER_BUYERS.equals(sourceMode)
                || isBlank(name) || isBlank(mobile) || isBlank(remark) || isBlank(cityName)
                || birthday == null || tagNames.isEmpty());
        if (needsEnrich) {
            try {
                YouzanCustomerPullClient.YouzanCustomerEnrichment enriched = client.enrichCustomer(
                        tenantId, shopId, yzOpenId, pulled.fansType(), pulled.fansId(), mobile);
                if (enriched != null) {
                    name = firstNonBlank(enriched.name(), name);
                    nickname = firstNonBlank(nickname, enriched.nickname());
                    mobile = firstNonBlank(mobile, enriched.mobile());
                    if (birthday == null) {
                        birthday = enriched.birthday();
                    }
                    remark = firstNonBlank(remark, enriched.remark());
                    cityName = firstNonBlank(cityName, enriched.cityName());
                    if (enriched.tagNames() != null) {
                        tagNames.addAll(enriched.tagNames());
                    }
                    if (!isBlank(enriched.warning())) {
                        warnings.add(sanitizeText(enriched.warning()));
                    }
                }
            } catch (RuntimeException ex) {
                warnings.add("部分客户资料补全失败，已按基础信息继续建档");
            }
        }
        return new ResolvedPulledProfile(name, nickname, mobile, birthday, remark, cityName, List.copyOf(tagNames));
    }

    private List<String> resolveTagCodesCached(long tenantId, CurrentUser operator, List<String> tagNames,
                                               Map<String, Optional<String>> tagCodeCache) {
        if (tagNames == null || tagNames.isEmpty()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        List<String> misses = new ArrayList<>();
        for (String tagName : tagNames) {
            String clean = trimToNull(tagName);
            if (clean == null) {
                continue;
            }
            String key = clean.toLowerCase(Locale.ROOT);
            Optional<String> cached = tagCodeCache.get(key);
            if (cached != null) {
                cached.ifPresent(result::add);
            } else {
                misses.add(clean);
            }
        }
        if (!misses.isEmpty()) {
            for (String miss : misses) {
                String key = miss.toLowerCase(Locale.ROOT);
                List<String> resolved = importService.resolveOrCreateTagCodes(tenantId, operator, List.of(miss));
                if (resolved != null && !resolved.isEmpty()) {
                    String code = resolved.get(0);
                    tagCodeCache.put(key, Optional.of(code));
                    result.add(code);
                } else {
                    tagCodeCache.put(key, Optional.empty());
                }
            }
        }
        return List.copyOf(result);
    }

    private String tryBackfillExisting(long tenantId, CurrentUser operator,
                                       CustomerExternalImportService.ExistingCustomerRef ref,
                                       String shopId, String sourceMode, ResolvedPulledProfile resolved,
                                       Map<String, Optional<String>> tagCodeCache, String defaultDetail) {
        boolean hasCandidate = !isBlank(resolved.cityName()) || !isBlank(resolved.remark())
                || resolved.birthday() != null || !resolved.tagNames().isEmpty();
        if (!hasCandidate) {
            return defaultDetail;
        }
        try {
            List<String> tagCodes = resolveTagCodesCached(tenantId, operator, resolved.tagNames(), tagCodeCache);
            CustomerExternalImportService.BackfillProfileResult backfill = importService.backfillMissingProfile(
                    tenantId, operator, ref.customerId(),
                    new CustomerExternalImportService.BackfillProfileCommand(
                            resolved.cityName(),
                            resolved.remark(),
                            resolved.birthday(),
                            tagCodes,
                            "有赞客户同步补全空缺资料（shopId=" + shopId + ", mode=" + sourceMode + "）"));
            if (backfill != null && backfill.updated() && !backfill.updatedFields().isEmpty()) {
                return "该有赞客户已绑定 CRM 档案 " + ref.customerNo()
                        + "，已补全空缺资料（" + String.join("、", backfill.updatedFields()) + "）";
            }
        } catch (RuntimeException ex) {
            LOG.warn("有赞已绑定客户补全空缺资料失败 customerId={}: {}", ref.customerId(), sanitizeText(ex.getMessage()));
        }
        return defaultDetail;
    }

    private void ensureYouzanCustomerLink(long tenantId, String shopId, String yzOpenId,
                                          long customerId, long actorUserId) {
        if (youzanRepository.findCustomerLink(tenantId, shopId, yzOpenId).isPresent()) {
            return;
        }
        try {
            youzanRepository.insertCustomerLink(tenantId, new YouzanCustomerLink(
                    null, shopId, yzOpenId, customerId,
                    LINK_TYPE_STRONG, LINK_CONFIDENCE_HIGH, LINK_REASON_AUTO,
                    actorUserId, null, null));
        } catch (DuplicateKeyException ignored) {
            // 并发写入同一有赞客户链接，幂等忽略
        }
    }

    private void linkUnresolvedRiskIssues(long tenantId, String shopId, String yzOpenId, long customerId) {
        try {
            List<Long> issueIds = youzanRepository.findIssueIdsWithoutOrderByExternalCustomer(
                    tenantId, shopId, yzOpenId);
            if (issueIds != null && !issueIds.isEmpty()) {
                riskLinkOperations.linkCustomerToYouzanIssues(tenantId,
                        issueIds.stream().map(Object::toString).toList(), customerId);
            }
        } catch (RuntimeException ex) {
            LOG.warn("关联有赞未解风控单失败 shopId={}, customerId={}: {}", shopId, customerId,
                    sanitizeText(ex.getMessage()));
        }
    }

    private void persistItem(long tenantId, long runId, String shopId, String externalCustomerId,
                             String outcome, String displayNameMasked, String mobileMasked,
                             Long crmCustomerId, String crmCustomerNo,
                             List<Long> conflictIds, List<String> conflictNos,
                             String detailMasked,
                             List<YouzanCustomerSyncItemResponse> inlineFallbackItems) {
        String idsJson = (conflictIds == null || conflictIds.isEmpty()) ? null : toJson(conflictIds);
        String nosJson = (conflictNos == null || conflictNos.isEmpty()) ? null : toJson(conflictNos);
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        YouzanCustomerSyncItem item = new YouzanCustomerSyncItem(
                null, tenantId, runId, shopId, truncate(externalCustomerId, 128), outcome,
                truncate(displayNameMasked, 96), truncate(mobileMasked, 32),
                crmCustomerId, truncate(crmCustomerNo, 48),
                idsJson, nosJson, truncate(detailMasked, 500), createdAt);
        syncRepository.insertItem(item);
        if (inlineFallbackItems.size() < INLINE_ITEMS_LIMIT) {
            inlineFallbackItems.add(new YouzanCustomerSyncItemResponse(
                    null, runId, shopId, item.externalCustomerId(), outcome,
                    item.displayNameMasked(), item.mobileMasked(),
                    crmCustomerId, item.crmCustomerNo(),
                    conflictIds == null ? List.of() : List.copyOf(conflictIds),
                    conflictNos == null ? List.of() : List.copyOf(conflictNos),
                    item.detailMasked(), createdAt));
        }
    }

    private YouzanCustomerCursorState initOrResumeCursor(String sourceMode,
                                                         YouzanCustomerSyncCursor existingCursor,
                                                         LocalDate startDate,
                                                         LocalDate endDate,
                                                         Instant now) {
        Instant reqStart = startDate == null ? null : startDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant reqEnd = endDate == null ? null
                : endDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).minusSeconds(1).toInstant();

        if (existingCursor != null && existingCursor.hasMore() && !isBlank(existingCursor.cursorJson())) {
            YouzanCustomerCursorState saved = readCursorState(existingCursor.cursorJson());
            if (saved != null && matchesRequestedRange(saved, reqStart, reqEnd)) {
                return saved;
            }
        }

        Instant anchorEnd = reqEnd != null ? reqEnd : now;
        Instant anchorStart;
        if (reqStart != null) {
            if (reqStart.isAfter(anchorEnd)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于截止时间");
            }
            anchorStart = reqStart;
        } else if (existingCursor != null && existingCursor.lastAnchorAt() != null
                && !existingCursor.lastAnchorAt().minus(INCREMENTAL_OVERLAP).isAfter(anchorEnd)) {
            anchorStart = existingCursor.lastAnchorAt().minus(INCREMENTAL_OVERLAP);
        } else if (SOURCE_MODE_ORDER_BUYERS.equals(sourceMode)) {
            anchorStart = anchorEnd.minus(ORDER_BUYERS_DEFAULT_LOOKBACK);
        } else {
            anchorStart = null;
        }

        Instant windowStart = anchorStart;
        Instant windowEnd;
        if (SOURCE_MODE_ORDER_BUYERS.equals(sourceMode) && windowStart != null) {
            Instant maxEnd = windowStart.plus(ORDER_BUYERS_MAX_WINDOW);
            windowEnd = maxEnd.isBefore(anchorEnd) ? maxEnd : anchorEnd;
        } else {
            windowEnd = anchorEnd;
        }
        return new YouzanCustomerCursorState(anchorStart, anchorEnd, windowStart, windowEnd, 1, 0);
    }

    private static boolean matchesRequestedRange(YouzanCustomerCursorState saved, Instant reqStart, Instant reqEnd) {
        if (reqStart != null && !Objects.equals(reqStart, saved.anchorStart())) {
            return false;
        }
        if (reqEnd != null && !Objects.equals(reqEnd, saved.anchorEnd())) {
            return false;
        }
        return true;
    }

    private YouzanCustomerPullClient.YouzanCustomerPage fetchPage(YouzanCustomerPullClient client,
                                                                  long tenantId, String shopId,
                                                                  String sourceMode,
                                                                  YouzanCustomerCursorState state,
                                                                  int pageSize) {
        if (SOURCE_MODE_SCRM_LIST.equals(sourceMode)) {
            return client.pullScrmPage(tenantId, shopId, state.windowStart(), state.windowEnd(),
                    state.pageNo(), pageSize);
        }
        return client.pullOrderBuyersPage(tenantId, shopId, state.windowStart(), state.windowEnd(),
                state.pageNo(), pageSize);
    }

    private YouzanCustomerCursorState bisectWindow(YouzanCustomerCursorState state) {
        Instant effectiveStart = state.windowStart() != null ? state.windowStart() : SCRM_FALLBACK_START;
        Instant effectiveEnd = state.windowEnd() != null ? state.windowEnd() : state.anchorEnd();
        if (effectiveEnd == null) {
            effectiveEnd = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        }
        long spanSeconds = Duration.between(effectiveStart, effectiveEnd).getSeconds();
        if (spanSeconds <= 1L) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                    "有赞当前时间窗客户数过于密集，无法继续二分切窗，请缩小日期范围后重试");
        }
        Instant midpoint = effectiveStart.plusSeconds(spanSeconds / 2L);
        return new YouzanCustomerCursorState(
                state.anchorStart(), state.anchorEnd(), effectiveStart, midpoint, 1, 0);
    }

    private YouzanCustomerCursorState advancePastPage(YouzanCustomerCursorState state,
                                                      boolean pageHasMore,
                                                      String sourceMode) {
        if (pageHasMore) {
            return new YouzanCustomerCursorState(
                    state.anchorStart(), state.anchorEnd(),
                    state.windowStart(), state.windowEnd(),
                    state.pageNo() + 1, 0);
        }
        if (state.windowEnd() != null && state.anchorEnd() != null && state.windowEnd().isBefore(state.anchorEnd())) {
            Instant nextWindowStart = state.windowEnd();
            Instant nextWindowEnd;
            if (SOURCE_MODE_ORDER_BUYERS.equals(sourceMode)) {
                Instant candidate = nextWindowStart.plus(ORDER_BUYERS_MAX_WINDOW);
                nextWindowEnd = candidate.isBefore(state.anchorEnd()) ? candidate : state.anchorEnd();
            } else {
                nextWindowEnd = state.anchorEnd();
            }
            return new YouzanCustomerCursorState(
                    state.anchorStart(), state.anchorEnd(),
                    nextWindowStart, nextWindowEnd,
                    1, 0);
        }
        return null;
    }

    private YouzanCustomerSyncStatusResponse buildStatusResponse(long tenantId, String shopId) {
        String adapterName = pullClientProvider.activeAdapterName(tenantId, shopId);
        boolean officialEnabled = pullClientProvider.isOfficialEnabled(tenantId);
        YouzanCustomerCursorSummary scrmSummary = buildCursorSummary(
                SOURCE_MODE_SCRM_LIST,
                syncRepository.findCursor(tenantId, shopId, SOURCE_MODE_SCRM_LIST).orElse(null));
        YouzanCustomerCursorSummary orderSummary = buildCursorSummary(
                SOURCE_MODE_ORDER_BUYERS,
                syncRepository.findCursor(tenantId, shopId, SOURCE_MODE_ORDER_BUYERS).orElse(null));
        return new YouzanCustomerSyncStatusResponse(shopId, adapterName, officialEnabled, scrmSummary, orderSummary);
    }

    private YouzanCustomerCursorSummary buildCursorSummary(String sourceMode, YouzanCustomerSyncCursor cursor) {
        if (cursor == null) {
            return new YouzanCustomerCursorSummary(
                    sourceMode, false, false, null, null, null, null, null, null, 0L);
        }
        YouzanCustomerCursorState state = isBlank(cursor.cursorJson()) ? null : readCursorState(cursor.cursorJson());
        return new YouzanCustomerCursorSummary(
                sourceMode,
                true,
                cursor.hasMore(),
                state == null ? null : state.pageNo(),
                state == null ? null : state.itemOffset(),
                state == null ? null : state.windowStart(),
                state == null ? null : state.windowEnd(),
                cursor.lastAnchorAt(),
                cursor.lastSyncedAt(),
                cursor.totalImportedCount());
    }

    private YouzanCustomerSyncRunResponse loadRunResponseWithItems(long tenantId, long runId) {
        YouzanCustomerSyncRun run = syncRepository.findRun(tenantId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "有赞客户同步批次不存在"));
        Map<Long, String> ownerNames = resolveOwnerNames(tenantId, List.of(run));
        List<YouzanCustomerSyncItemResponse> items = syncRepository
                .listItems(tenantId, runId, null, 0, INLINE_ITEMS_LIMIT)
                .stream()
                .map(this::toItemResponse)
                .toList();
        return toRunResponse(run, ownerNames.get(run.ownerStaffId()), items);
    }

    private Map<Long, String> resolveOwnerNames(long tenantId, List<YouzanCustomerSyncRun> runs) {
        if (runs == null || runs.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = runs.stream().map(YouzanCustomerSyncRun::ownerStaffId).distinct().toList();
        List<UserRepository.StewardOptionRow> profiles = userRepository.findStaffProfilesByIds(tenantId, ids);
        Map<Long, String> result = new HashMap<>();
        if (profiles != null) {
            for (UserRepository.StewardOptionRow row : profiles) {
                if (row != null && row.id() != null) {
                    result.put(row.id(), row.displayName());
                }
            }
        }
        return result;
    }

    private YouzanCustomerSyncRunResponse toRunResponse(YouzanCustomerSyncRun run, String ownerName,
                                                        List<YouzanCustomerSyncItemResponse> items) {
        return new YouzanCustomerSyncRunResponse(
                run.id(),
                run.shopId(),
                run.sourceMode(),
                run.pullMode(),
                run.requestedLimit(),
                run.ownerStaffId(),
                ownerName,
                run.adapterName(),
                run.status(),
                run.fetchedCount(),
                run.createdCount(),
                run.skippedExistingCount(),
                run.mobileConflictCount(),
                run.failedCount(),
                run.hasMore(),
                parseStringList(run.warningsJson()),
                run.errorMasked(),
                run.startedAt(),
                run.finishedAt(),
                items);
    }

    private YouzanCustomerSyncItemResponse toItemResponse(YouzanCustomerSyncItem item) {
        return new YouzanCustomerSyncItemResponse(
                item.id(),
                item.runId(),
                item.shopId(),
                item.externalCustomerId(),
                item.outcome(),
                item.displayNameMasked(),
                item.mobileMasked(),
                item.crmCustomerId(),
                item.crmCustomerNo(),
                parseLongList(item.conflictCustomerIdsJson()),
                parseStringList(item.conflictCustomerNosJson()),
                item.detailMasked(),
                item.createdAt());
    }

    private void recordPullAudit(long tenantId, long actorUserId, long runId, String shopId,
                                 String sourceMode, String pullMode, Integer requestedLimit,
                                 long ownerStaffId, String adapterName, String status,
                                 int fetchedCount, int createdCount, int skippedExistingCount,
                                 int mobileConflictCount, int failedCount, boolean hasMore,
                                 boolean resetCursor, int warningCount) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("runId", runId);
        after.put("shopId", shopId);
        after.put("sourceMode", sourceMode);
        after.put("pullMode", pullMode);
        after.put("requestedLimit", requestedLimit);
        after.put("ownerStaffId", ownerStaffId);
        after.put("adapterName", adapterName);
        after.put("status", status);
        after.put("fetchedCount", fetchedCount);
        after.put("createdCount", createdCount);
        after.put("skippedExistingCount", skippedExistingCount);
        after.put("mobileConflictCount", mobileConflictCount);
        after.put("failedCount", failedCount);
        after.put("hasMore", hasMore);
        after.put("resetCursor", resetCursor);
        after.put("warningCount", warningCount);
        auditService.record(tenantId, actorUserId, AuditService.ACTION_YOUZAN_CUSTOMER_SYNC,
                AUDIT_TARGET_TYPE, Long.toString(runId), null, after,
                "执行有赞客户拉取与CRM正式建档");
    }

    private Map<String, Object> cursorAuditSnapshot(YouzanCustomerSyncCursor cursor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("shopId", cursor.shopId());
        map.put("sourceMode", cursor.sourceMode());
        map.put("hasMore", cursor.hasMore());
        map.put("lastAnchorAt", cursor.lastAnchorAt());
        map.put("lastSyncedAt", cursor.lastSyncedAt());
        map.put("totalImportedCount", cursor.totalImportedCount());
        return map;
    }

    private String buildCanonicalPullPayload(String shopId, String sourceMode, String pullMode, Integer limit,
                                             long ownerStaffId, String sourceCode, String channelCode,
                                             LocalDate startDate, LocalDate endDate,
                                             boolean enrichProfile, boolean resetCursor) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("shopId", shopId);
        canonical.put("sourceMode", sourceMode);
        canonical.put("pullMode", pullMode);
        canonical.put("limit", limit);
        canonical.put("ownerStaffId", ownerStaffId);
        canonical.put("sourceCode", trimToNull(sourceCode));
        canonical.put("channelCode", trimToNull(channelCode));
        canonical.put("startDate", startDate == null ? null : startDate.toString());
        canonical.put("endDate", endDate == null ? null : endDate.toString());
        canonical.put("enrichProfile", enrichProfile);
        canonical.put("resetCursor", resetCursor);
        return toJson(canonical);
    }

    private YouzanCustomerCursorState readCursorState(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, YouzanCustomerCursorState.class);
        } catch (Exception ex) {
            LOG.warn("解析有赞客户同步游标失败，将忽略旧游标: {}", sanitizeText(ex.getMessage()));
            return null;
        }
    }

    private List<String> parseStringList(String json) {
        if (isBlank(json)) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, STRING_LIST_TYPE);
            return list == null ? List.of() : list;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<Long> parseLongList(String json) {
        if (isBlank(json)) {
            return List.of();
        }
        try {
            List<Long> list = objectMapper.readValue(json, LONG_LIST_TYPE);
            return list == null ? List.of() : list;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("有赞客户同步数据序列化失败", ex);
        }
    }

    private static void requireEmployee(CurrentUser operator) {
        if (operator == null || operator.isInstitutionUser() || !"EMPLOYEE".equals(operator.userType())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅内部员工可访问有赞客户同步功能");
        }
    }

    private static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供1至128字符的幂等键");
        }
        return key.strip();
    }

    private static String normalizeShopId(String raw) {
        String clean = trimToNull(raw);
        if (clean == null || clean.length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "店铺编号不能为空且不能超过64字符");
        }
        return clean;
    }

    private static String normalizeSourceMode(String raw) {
        String clean = trimToNull(raw);
        if (clean == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "数据源模式不能为空");
        }
        String upper = clean.toUpperCase(Locale.ROOT);
        if (!SOURCE_MODE_SCRM_LIST.equals(upper) && !SOURCE_MODE_ORDER_BUYERS.equals(upper)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的有赞客户数据源模式: " + clean);
        }
        return upper;
    }

    private static String normalizePullMode(String raw) {
        String clean = trimToNull(raw);
        if (clean == null) {
            return PULL_MODE_BATCH;
        }
        String upper = clean.toUpperCase(Locale.ROOT);
        if (!PULL_MODE_BATCH.equals(upper) && !PULL_MODE_ALL.equals(upper)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的拉取模式: " + clean);
        }
        return upper;
    }

    private static String normalizeOptionalOutcome(String raw) {
        String clean = trimToNull(raw);
        if (clean == null) {
            return null;
        }
        String upper = clean.toUpperCase(Locale.ROOT);
        if (!VALID_OUTCOMES.contains(upper)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的结果状态筛选: " + clean);
        }
        return upper;
    }

    private static int resolveTargetCount(String pullMode, Integer requestedLimit) {
        int fallback = PULL_MODE_ALL.equals(pullMode) ? DEFAULT_ALL_LIMIT : DEFAULT_BATCH_LIMIT;
        if (requestedLimit == null) {
            return fallback;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "单次拉取条数必须在1至500之间");
        }
        return requestedLimit;
    }

    private static int resolveMaxPages(String pullMode, int targetCount, int pageSize) {
        if (PULL_MODE_ALL.equals(pullMode)) {
            return MAX_ALL_PAGES_PER_RUN;
        }
        int minNeeded = (targetCount + pageSize - 1) / pageSize;
        return Math.min(MAX_BATCH_PAGES_CAP, Math.max(MAX_ALL_PAGES_PER_RUN, minNeeded + 15));
    }

    private static LocalDate parseOptionalDate(String raw, String fieldLabel) {
        String clean = trimToNull(raw);
        if (clean == null) {
            return null;
        }
        try {
            return LocalDate.parse(clean);
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, fieldLabel + "格式须为 yyyy-MM-dd");
        }
    }

    private static long parseRunId(String resourceId) {
        try {
            return Long.parseLong(resourceId);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "幂等重放批次ID无效");
        }
    }

    private static Instant maxInstant(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    private static String maskDisplayName(String primary, String secondary) {
        String raw = firstNonBlank(primary, secondary);
        if (raw == null) {
            return null;
        }
        return sanitizeText(raw);
    }

    private static String maskRawMobile(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String compact = raw.replaceAll("[\\s-]+", "");
        if (compact.startsWith("+86") && compact.length() == 14) {
            compact = compact.substring(3);
        }
        if (compact.matches("^1[3-9]\\d{9}$")) {
            return MobileSupport.mask(compact);
        }
        return null;
    }

    private static String sanitizeText(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = CN_MOBILE_PATTERN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(1) + "****" + matcher.group(3));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private record ItemProcessResult(boolean recorded, String outcome) {
    }

    private record ResolvedPulledProfile(
            String name,
            String nickname,
            String mobile,
            LocalDate birthday,
            String remark,
            String cityName,
            List<String> tagNames
    ) {
        private ResolvedPulledProfile {
            tagNames = tagNames == null ? List.of() : List.copyOf(tagNames);
        }
    }
}
