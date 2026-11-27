package com.whale.crm.integration.youzan.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.whale.crm.customer.application.CustomerExternalImportService;
import com.whale.crm.customer.application.MobileSupport;
import com.whale.crm.integration.youzan.application.YouzanRiskLinkOperations;
import com.whale.crm.integration.youzan.customer.adapter.YouzanCustomerPullClient;
import com.whale.crm.integration.youzan.customer.adapter.YouzanCustomerPullClientRouter;
import com.whale.crm.integration.youzan.customer.adapter.YouzanCustomerWindowTooLargeException;
import com.whale.crm.integration.youzan.customer.api.dto.YouzanCustomerPullRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouzanCustomerSyncServiceTest {

    private static final long TENANT_ID = 1L;
    private static final String SHOP_ID = "SHOP-SYNC-001";
    private static final long OWNER_ID = 2L;
    private static final CurrentUser ADMIN = new CurrentUser(
            1L, TENANT_ID, "admin", "管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("integration:youzan:manage", "customer:write"), Set.of());
    private static final CurrentUser INSTITUTION_USER = new CurrentUser(
            8L, TENANT_ID, "inst1", "机构前台", "INSTITUTION", null,
            Set.of("INSTITUTION_STAFF"), Set.of("integration:youzan:manage", "customer:write"), Set.of(1L));

    private ObjectMapper objectMapper;
    private YouzanCustomerPullClientRouter clientRouter;
    private YouzanCustomerPullClient pullClient;
    private InMemorySyncRepository syncRepository;
    private YouzanRepository youzanRepository;
    private CustomerExternalImportService importService;
    private UserRepository userRepository;
    private IdempotencyService idempotencyService;
    private AuditService auditService;
    private YouzanRiskLinkOperations riskLinkOperations;
    private MutableClock clock;
    private YouzanCustomerSyncService service;

    private Map<String, YouzanCustomerLink> linksByOpenId;
    private Map<Long, String> crmCustomerNoById;
    private Map<String, Long> crmCustomerIdByMobile;
    private AtomicLong customerIdSeq;
    private Map<String, IdempotencyRecord> idempotencyStore;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        clientRouter = mock(YouzanCustomerPullClientRouter.class);
        pullClient = mock(YouzanCustomerPullClient.class);
        syncRepository = new InMemorySyncRepository();
        youzanRepository = mock(YouzanRepository.class);
        importService = mock(CustomerExternalImportService.class);
        userRepository = mock(UserRepository.class);
        idempotencyService = mock(IdempotencyService.class);
        auditService = mock(AuditService.class);
        riskLinkOperations = mock(YouzanRiskLinkOperations.class);
        clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));

        linksByOpenId = new LinkedHashMap<>();
        crmCustomerNoById = new HashMap<>();
        crmCustomerIdByMobile = new HashMap<>();
        customerIdSeq = new AtomicLong(5000L);
        idempotencyStore = new HashMap<>();

        when(pullClient.name()).thenReturn("YOUZAN_OFFICIAL");
        when(clientRouter.resolve(TENANT_ID, SHOP_ID)).thenReturn(pullClient);
        when(clientRouter.activeAdapterName(TENANT_ID, SHOP_ID)).thenReturn("YOUZAN_OFFICIAL");
        when(clientRouter.isOfficialEnabled(TENANT_ID)).thenReturn(true);

        when(importService.validateImportTarget(eq(TENANT_ID), eq(ADMIN), eq(OWNER_ID), any(), any()))
                .thenReturn(new CustomerExternalImportService.ValidatedImportTarget(OWNER_ID, "王丽", 1L, null, null));
        when(userRepository.findStaffProfilesByIds(eq(TENANT_ID), any()))
                .thenReturn(List.of(new UserRepository.StewardOptionRow(OWNER_ID, "王丽", 1L, "管家一组")));

        when(youzanRepository.findCustomerLink(eq(TENANT_ID), eq(SHOP_ID), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(linksByOpenId.get(inv.getArgument(2))));
        org.mockito.Mockito.doAnswer(inv -> {
            YouzanCustomerLink link = inv.getArgument(1);
            linksByOpenId.put(link.externalCustomerId(), link);
            return null;
        }).when(youzanRepository).insertCustomerLink(eq(TENANT_ID), any(YouzanCustomerLink.class));
        when(youzanRepository.findIssueIdsWithoutOrderByExternalCustomer(eq(TENANT_ID), eq(SHOP_ID), anyString()))
                .thenReturn(List.of());

        when(importService.ensureIdentityLinked(eq(TENANT_ID), anyLong(), eq("YOUZAN"), eq(SHOP_ID), anyString()))
                .thenAnswer(inv -> {
                    long cid = inv.getArgument(1);
                    String no = crmCustomerNoById.get(cid);
                    if (no == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new CustomerExternalImportService.ExistingCustomerRef(cid, no, "已建档客户", "139****0000"));
                });

        when(importService.importCustomer(eq(TENANT_ID), eq(ADMIN), any(), any()))
                .thenAnswer(inv -> {
                    CustomerExternalImportService.ExternalCustomerCommand cmd = inv.getArgument(3);
                    if (cmd.mobile() != null && crmCustomerIdByMobile.containsKey(cmd.mobile())) {
                        Long conflictId = crmCustomerIdByMobile.get(cmd.mobile());
                        String conflictNo = crmCustomerNoById.get(conflictId);
                        return new CustomerExternalImportService.ExternalImportResult(
                                CustomerExternalImportService.OUTCOME_MOBILE_CONFLICT,
                                null, null, cmd.displayName(), MobileSupport.mask(cmd.mobile()),
                                List.of(conflictId), List.of(conflictNo),
                                "手机号命中已有 CRM 客户（" + conflictNo + "），已跳过建档");
                    }
                    long id = customerIdSeq.incrementAndGet();
                    String no = String.format("C%010d", id);
                    crmCustomerNoById.put(id, no);
                    if (cmd.mobile() != null) {
                        crmCustomerIdByMobile.put(cmd.mobile(), id);
                    }
                    return new CustomerExternalImportService.ExternalImportResult(
                            CustomerExternalImportService.OUTCOME_CREATED,
                            id, no, cmd.displayName(),
                            cmd.mobile() == null ? null : MobileSupport.mask(cmd.mobile()),
                            List.of(), List.of(), "新建 CRM 客户 " + no);
                });

        when(idempotencyService.lookup(eq(TENANT_ID), anyString(), anyLong(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(3);
                    String payload = inv.getArgument(4);
                    IdempotencyRecord rec = idempotencyStore.get(key);
                    if (rec == null) {
                        return Optional.empty();
                    }
                    if (!rec.getRequestHash().equals(IdempotencyService.sha256(payload))) {
                        throw new BusinessException(ErrorCode.CONFLICT, "幂等键冲突");
                    }
                    return Optional.of(rec);
                });
        org.mockito.Mockito.doAnswer(inv -> {
            String key = inv.getArgument(3);
            String resId = inv.getArgument(5);
            String payload = inv.getArgument(6);
            IdempotencyRecord rec = new IdempotencyRecord();
            rec.setIdempotencyKey(key);
            rec.setResourceId(resId);
            rec.setRequestHash(IdempotencyService.sha256(payload));
            idempotencyStore.put(key, rec);
            return null;
        }).when(idempotencyService).recordSuccess(eq(TENANT_ID), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString());

        service = new YouzanCustomerSyncService(
                clientRouter, syncRepository, youzanRepository, importService, userRepository,
                idempotencyService, auditService, objectMapper, riskLinkOperations, clock);
    }

    @Test
    void sequentialPulls10Then10Then20ThenAll_advanceCursorWithZeroDuplicatesAndSwitchToIncremental() {
        List<YouzanCustomerPullClient.YouzanPulledCustomer> dataset = new ArrayList<>();
        Instant base = Instant.parse("2026-06-01T00:00:00Z");
        for (int i = 1; i <= 55; i++) {
            dataset.add(new YouzanCustomerPullClient.YouzanPulledCustomer(
                    String.format("YZ-CUST-%03d", i),
                    "客户" + i, "昵称" + i, String.format("1391000%04d", i),
                    "FEMALE", null, base.plus(i, ChronoUnit.DAYS), 1, 1000L + i, null));
        }

        List<Integer> observedPageSizes = new ArrayList<>();
        List<Instant> observedStarts = new ArrayList<>();
        when(pullClient.pullScrmPage(eq(TENANT_ID), eq(SHOP_ID), any(), any(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    Instant start = inv.getArgument(2);
                    Instant end = inv.getArgument(3);
                    int pageNo = inv.getArgument(4);
                    int pageSize = inv.getArgument(5);
                    observedPageSizes.add(pageSize);
                    observedStarts.add(start);
                    return slice(dataset, start, end, pageNo, pageSize);
                });

        // 1. 拉 10 条
        YouzanCustomerSyncRunResponse r1 = service.pull(ADMIN, pullReq("SCRM_LIST", "BATCH", 10, false, false), "k-1");
        assertThat(r1.createdCount()).isEqualTo(10);
        assertThat(r1.skippedExistingCount()).isZero();
        assertThat(r1.hasMore()).isTrue();
        YouzanCustomerSyncStatusResponse s1 = service.getStatus(ADMIN, SHOP_ID);
        assertThat(s1.scrmCursor().pageNo()).isEqualTo(1);
        assertThat(s1.scrmCursor().itemOffset()).isEqualTo(10);
        assertThat(s1.scrmCursor().totalImportedCount()).isEqualTo(10L);

        // 2. 再拉 10 条（刚好消耗完第 1 页第 11~20 条，推进到第 2 页偏移 0）
        YouzanCustomerSyncRunResponse r2 = service.pull(ADMIN, pullReq("SCRM_LIST", "BATCH", 10, false, false), "k-2");
        assertThat(r2.createdCount()).isEqualTo(10);
        assertThat(r2.hasMore()).isTrue();
        YouzanCustomerSyncStatusResponse s2 = service.getStatus(ADMIN, SHOP_ID);
        assertThat(s2.scrmCursor().pageNo()).isEqualTo(2);
        assertThat(s2.scrmCursor().itemOffset()).isZero();
        assertThat(s2.scrmCursor().totalImportedCount()).isEqualTo(20L);

        // 3. 切 20 条（消耗第 2 页第 21~40 条，推进到第 3 页偏移 0）
        YouzanCustomerSyncRunResponse r3 = service.pull(ADMIN, pullReq("SCRM_LIST", "BATCH", 20, false, false), "k-3");
        assertThat(r3.createdCount()).isEqualTo(20);
        assertThat(r3.hasMore()).isTrue();
        YouzanCustomerSyncStatusResponse s3 = service.getStatus(ADMIN, SHOP_ID);
        assertThat(s3.scrmCursor().pageNo()).isEqualTo(3);
        assertThat(s3.scrmCursor().itemOffset()).isZero();
        assertThat(s3.scrmCursor().totalImportedCount()).isEqualTo(40L);

        // 4. 切 ALL（拉完剩余 41~55 条，hasMore=false 并记录 lastAnchorAt）
        YouzanCustomerSyncRunResponse r4 = service.pull(ADMIN, pullReq("SCRM_LIST", "ALL", null, false, false), "k-4");
        assertThat(r4.createdCount()).isEqualTo(15);
        assertThat(r4.hasMore()).isFalse();
        assertThat(r4.requestedLimit()).isNull();
        YouzanCustomerSyncStatusResponse s4 = service.getStatus(ADMIN, SHOP_ID);
        assertThat(s4.scrmCursor().hasCursor()).isTrue();
        assertThat(s4.scrmCursor().hasMore()).isFalse();
        assertThat(s4.scrmCursor().totalImportedCount()).isEqualTo(55L);
        assertThat(s4.scrmCursor().lastAnchorAt()).isEqualTo(Instant.parse("2026-11-26T10:00:00Z"));

        // 底层调用全部严格固定页大小 20，且 55 位客户无重复无遗漏
        assertThat(observedPageSizes).containsOnly(20);
        assertThat(linksByOpenId).hasSize(55);

        // 5. 自动增量模式：5 分钟后新增 2 位客户 + 1 位落在 overlap 60s 内的老客户
        Instant previousAnchor = s4.scrmCursor().lastAnchorAt();
        clock.advance(Duration.ofMinutes(5));
        dataset.add(new YouzanCustomerPullClient.YouzanPulledCustomer(
                "YZ-CUST-001", "客户1", "昵称1", "13910000001", "FEMALE", null,
                previousAnchor.minusSeconds(30), 1, 1001L, null));
        dataset.add(new YouzanCustomerPullClient.YouzanPulledCustomer(
                "YZ-CUST-056", "增量客户56", "新56", "13910000056", "FEMALE", null,
                previousAnchor.plusSeconds(60), 1, 1056L, null));
        dataset.add(new YouzanCustomerPullClient.YouzanPulledCustomer(
                "YZ-CUST-057", "增量客户57", "新57", "13910000057", "FEMALE", null,
                previousAnchor.plusSeconds(120), 1, 1057L, null));

        observedStarts.clear();
        YouzanCustomerSyncRunResponse r5 = service.pull(ADMIN, pullReq("SCRM_LIST", "BATCH", 20, false, false), "k-5");
        assertThat(observedStarts).containsExactly(previousAnchor.minusSeconds(60));
        assertThat(r5.createdCount()).isEqualTo(2);
        assertThat(r5.skippedExistingCount()).isEqualTo(1);
        assertThat(r5.hasMore()).isFalse();
        assertThat(service.getStatus(ADMIN, SHOP_ID).scrmCursor().totalImportedCount()).isEqualTo(57L);

        // 6. 重置游标后再拉：清空游标从头扫描，已绑定的客户全部跳过不重复建档
        YouzanCustomerSyncStatusResponse afterReset = service.resetCursor(ADMIN, SHOP_ID, "SCRM_LIST");
        assertThat(afterReset.scrmCursor().hasCursor()).isFalse();
        assertThat(afterReset.scrmCursor().totalImportedCount()).isZero();
        verify(auditService).record(eq(TENANT_ID), eq(ADMIN.userId()),
                eq(AuditService.ACTION_YOUZAN_CUSTOMER_CURSOR_RESET), anyString(), anyString(), any(), any(), any());

        observedStarts.clear();
        YouzanCustomerSyncRunResponse r6 = service.pull(ADMIN, pullReq("SCRM_LIST", "ALL", null, false, false), "k-6");
        assertThat(observedStarts.get(0)).isNull();
        assertThat(r6.createdCount()).isZero();
        assertThat(r6.skippedExistingCount()).isEqualTo(57);
        assertThat(linksByOpenId).hasSize(57);
    }

    @Test
    void mobileConflictSkipsWithoutOverwritingLegacyCustomerAndLinksRiskIssuesForCreated() {
        crmCustomerNoById.put(1001L, "C0000001001");
        crmCustomerIdByMobile.put("13988881111", 1001L);
        when(youzanRepository.findIssueIdsWithoutOrderByExternalCustomer(TENANT_ID, SHOP_ID, "YZ-NEW-OK"))
                .thenReturn(List.of(91L, 92L));

        List<YouzanCustomerPullClient.YouzanPulledCustomer> pageItems = List.of(
                new YouzanCustomerPullClient.YouzanPulledCustomer(
                        "YZ-CONFLICT", "冲突买家", "冲突昵称", "13988881111",
                        "FEMALE", null, Instant.parse("2026-06-05T00:00:00Z"), 1, 101L, null),
                new YouzanCustomerPullClient.YouzanPulledCustomer(
                        "YZ-NEW-OK", "正常新客", "新客昵称", "13988882222",
                        "FEMALE", null, Instant.parse("2026-06-06T00:00:00Z"), 1, 102L, null));
        when(pullClient.pullScrmPage(eq(TENANT_ID), eq(SHOP_ID), any(), any(), eq(1), eq(20)))
                .thenReturn(new YouzanCustomerPullClient.YouzanCustomerPage(pageItems, 2, false, List.of()));

        YouzanCustomerSyncRunResponse run = service.pull(ADMIN, pullReq("SCRM_LIST", "BATCH", 20, false, false), "k-conf");
        assertThat(run.status()).isEqualTo(YouzanCustomerSyncService.STATUS_PARTIAL);
        assertThat(run.createdCount()).isEqualTo(1);
        assertThat(run.mobileConflictCount()).isEqualTo(1);
        assertThat(linksByOpenId).doesNotContainKey("YZ-CONFLICT").containsKey("YZ-NEW-OK");

        var conflictItem = run.items().stream()
                .filter(i -> "YZ-CONFLICT".equals(i.externalCustomerId())).findFirst().orElseThrow();
        assertThat(conflictItem.outcome()).isEqualTo(CustomerExternalImportService.OUTCOME_MOBILE_CONFLICT);
        assertThat(conflictItem.mobileMasked()).isEqualTo("139****1111");
        assertThat(conflictItem.conflictCustomerIds()).containsExactly(1001L);
        assertThat(conflictItem.conflictCustomerNos()).containsExactly("C0000001001");
        assertThat(conflictItem.detailMasked()).doesNotContain("13988881111");

        Long createdId = linksByOpenId.get("YZ-NEW-OK").customerId();
        verify(riskLinkOperations).linkCustomerToYouzanIssues(TENANT_ID, List.of("91", "92"), createdId);
    }

    @Test
    void concurrencyLockRejectsDuplicateRunAndReleasesLockOnUnexpectedFailure() {
        // 首次远程调用抛异常，验证 finally 可靠释放锁且二次请求能重新执行
        when(pullClient.pullScrmPage(eq(TENANT_ID), eq(SHOP_ID), any(), any(), eq(1), eq(20)))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "网络抖动，含敏感13912345678测试"))
                .thenReturn(new YouzanCustomerPullClient.YouzanCustomerPage(List.of(), 0, false, List.of()));

        assertThatThrownBy(() -> service.pull(ADMIN, pullReq("SCRM_LIST", "BATCH", 10, false, false), "k-err-1"))
                .isInstanceOf(BusinessException.class);

        YouzanCustomerSyncRun failedRun = syncRepository.findRun(TENANT_ID, 1L).orElseThrow();
        assertThat(failedRun.status()).isEqualTo(YouzanCustomerSyncService.STATUS_FAILED);
        assertThat(failedRun.errorMasked()).contains("139****5678").doesNotContain("13912345678");
        assertThat(syncRepository.hasActiveRunningLock(TENANT_ID, SHOP_ID, "SCRM_LIST")).isFalse();

        // 锁已释放，立即重试可成功
        YouzanCustomerSyncRunResponse retryOk = service.pull(ADMIN, pullReq("SCRM_LIST", "BATCH", 10, false, false), "k-err-2");
        assertThat(retryOk.status()).isEqualTo(YouzanCustomerSyncService.STATUS_SUCCEEDED);

        // 模拟存在活跃 RUNNING 锁 → 返回 409 CONFLICT
        syncRepository.tryStartRun(TENANT_ID, SHOP_ID, "SCRM_LIST", "BATCH", 10, OWNER_ID,
                "YOUZAN_OFFICIAL", ADMIN.userId(), clock.instant(), clock.instant().plusSeconds(300));
        assertThatThrownBy(() -> service.pull(ADMIN, pullReq("SCRM_LIST", "BATCH", 10, false, false), "k-lock"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void windowTooLargeAutomaticallyBisectsFromFallback2015AndCoversBothHalves() {
        Instant earlyTs = Instant.parse("2018-03-15T00:00:00Z");
        Instant lateTs = Instant.parse("2025-08-20T00:00:00Z");
        List<YouzanCustomerPullClient.YouzanPulledCustomer> all = List.of(
                new YouzanCustomerPullClient.YouzanPulledCustomer("YZ-EARLY", "早期客户", null, "13966660001",
                        "FEMALE", null, earlyTs, null, null, null),
                new YouzanCustomerPullClient.YouzanPulledCustomer("YZ-LATE", "近期客户", null, "13966660002",
                        "FEMALE", null, lateTs, null, null, null));

        AtomicInteger probeCount = new AtomicInteger();
        when(pullClient.pullScrmPage(eq(TENANT_ID), eq(SHOP_ID), any(), any(), anyInt(), eq(20)))
                .thenAnswer(inv -> {
                    Instant start = inv.getArgument(2);
                    Instant end = inv.getArgument(3);
                    int pageNo = inv.getArgument(4);
                    probeCount.incrementAndGet();
                    if (start == null) {
                        throw new YouzanCustomerWindowTooLargeException(12000L);
                    }
                    return slice(all, start, end, pageNo, 20);
                });

        YouzanCustomerSyncRunResponse run = service.pull(ADMIN, pullReq("SCRM_LIST", "ALL", null, false, false), "k-bisect");
        assertThat(run.createdCount()).isEqualTo(2);
        assertThat(run.hasMore()).isFalse();
        assertThat(run.warnings()).anyMatch(w -> w.contains("二分切窗"));
        assertThat(probeCount.get()).isGreaterThanOrEqualTo(3);
        assertThat(linksByOpenId).containsKeys("YZ-EARLY", "YZ-LATE");
    }

    @Test
    void orderBuyersSlicesAtMost89DaysEnrichesWhenEnabledAndRejectsInstitutionUser() {
        List<Duration> windowSpans = new ArrayList<>();
        YouzanCustomerPullClient.YouzanPulledCustomer repeatBuyer = new YouzanCustomerPullClient.YouzanPulledCustomer(
                "YZ-ORD-001", null, "买家昵称", null, "UNKNOWN", null,
                Instant.parse("2026-06-10T00:00:00Z"), 1, 7001L, null);
        when(pullClient.pullOrderBuyersPage(eq(TENANT_ID), eq(SHOP_ID), any(), any(), anyInt(), eq(50)))
                .thenAnswer(inv -> {
                    Instant s = inv.getArgument(2);
                    Instant e = inv.getArgument(3);
                    windowSpans.add(Duration.between(s, e));
                    if (!repeatBuyer.createdAt().isBefore(s) && !repeatBuyer.createdAt().isAfter(e)) {
                        return new YouzanCustomerPullClient.YouzanCustomerPage(
                                List.of(repeatBuyer, repeatBuyer), 2, false, List.of());
                    }
                    return new YouzanCustomerPullClient.YouzanCustomerPage(List.of(), 0, false, List.of());
                });
        when(pullClient.enrichCustomer(TENANT_ID, SHOP_ID, "YZ-ORD-001", 1, 7001L, null))
                .thenReturn(new YouzanCustomerPullClient.YouzanCustomerEnrichment(
                        "补全真名", "补全昵称", "13955550001", "FEMALE", null, null));

        YouzanCustomerSyncRunResponse run = service.pull(ADMIN,
                pullReq("ORDER_BUYERS", "ALL", null, true, false), "k-ord-1");
        assertThat(run.fetchedCount()).isEqualTo(1);
        assertThat(run.createdCount()).isEqualTo(1);
        assertThat(run.hasMore()).isFalse();
        assertThat(windowSpans).isNotEmpty().allSatisfy(span -> assertThat(span).isLessThanOrEqualTo(Duration.ofDays(89)));
        verify(pullClient, times(1)).enrichCustomer(TENANT_ID, SHOP_ID, "YZ-ORD-001", 1, 7001L, null);

        // 幂等重放不触发新拉取
        YouzanCustomerSyncRunResponse replayed = service.pull(ADMIN,
                pullReq("ORDER_BUYERS", "ALL", null, true, false), "k-ord-1");
        assertThat(replayed.id()).isEqualTo(run.id());
        verify(pullClient, times(1)).enrichCustomer(TENANT_ID, SHOP_ID, "YZ-ORD-001", 1, 7001L, null);

        // enrichProfile=false 不调用 enrichCustomer
        YouzanCustomerPullClient.YouzanPulledCustomer secondBuyer = new YouzanCustomerPullClient.YouzanPulledCustomer(
                "YZ-ORD-002", null, "仅昵称", null, "UNKNOWN", null,
                Instant.parse("2026-06-12T00:00:00Z"), 1, 7002L, null);
        when(pullClient.pullOrderBuyersPage(eq(TENANT_ID), eq(SHOP_ID), any(), any(), anyInt(), eq(50)))
                .thenReturn(new YouzanCustomerPullClient.YouzanCustomerPage(List.of(secondBuyer), 1, false, List.of()));
        service.pull(ADMIN, pullReq("ORDER_BUYERS", "BATCH", 10, false, true), "k-ord-no-enrich");
        verify(pullClient, never()).enrichCustomer(eq(TENANT_ID), eq(SHOP_ID), eq("YZ-ORD-002"), any(), any(), any());

        // 机构账号在全部入口均被拒绝
        assertThatThrownBy(() -> service.getStatus(INSTITUTION_USER, SHOP_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.pull(INSTITUTION_USER, pullReq("SCRM_LIST", "BATCH", 10, false, false), "k-inst"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.resetCursor(INSTITUTION_USER, SHOP_ID, "SCRM_LIST"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void allModeStopsAt30PageSafetyCapWithHasMoreTrueForFrontendContinuation() {
        // 模拟前 35 页全部为已绑定老客户，验证 ALL 模式最多扫 30 页即停止并保存第 31 页断点
        crmCustomerNoById.put(2001L, "C0000002001");
        when(pullClient.pullScrmPage(eq(TENANT_ID), eq(SHOP_ID), any(), any(), anyInt(), eq(20)))
                .thenAnswer(inv -> {
                    int pageNo = inv.getArgument(4);
                    List<YouzanCustomerPullClient.YouzanPulledCustomer> pageItems = new ArrayList<>();
                    for (int i = 0; i < 20; i++) {
                        String oid = "YZ-BOUND-" + pageNo + "-" + i;
                        linksByOpenId.put(oid, new YouzanCustomerLink(
                                1L, SHOP_ID, oid, 2001L, "STRONG", "HIGH", "seed", 1L, clock.instant(), 0L));
                        pageItems.add(new YouzanCustomerPullClient.YouzanPulledCustomer(
                                oid, "已绑客户", null, null, "UNKNOWN", null, clock.instant(), null, null, null));
                    }
                    return new YouzanCustomerPullClient.YouzanCustomerPage(pageItems, 700, pageNo < 35, List.of());
                });

        YouzanCustomerSyncRunResponse run = service.pull(ADMIN, pullReq("SCRM_LIST", "ALL", null, false, false), "k-cap30");
        assertThat(run.hasMore()).isTrue();
        assertThat(run.skippedExistingCount()).isEqualTo(600);
        assertThat(run.createdCount()).isZero();
        assertThat(run.warnings()).anyMatch(w -> w.contains("30页"));
        YouzanCustomerSyncStatusResponse status = service.getStatus(ADMIN, SHOP_ID);
        assertThat(status.scrmCursor().pageNo()).isEqualTo(31);
        assertThat(status.scrmCursor().itemOffset()).isZero();
    }

    @Test
    void enrichProfileEnrichesScrmDetailsAndNonDestructivelyBackfillsExistingBoundCustomer() {
        // 1. 新客户在 SCRM_LIST 已返回姓名与手机，但缺少备注/城市/标签 → enrichProfile=true 时必须调用 enrichCustomer
        YouzanCustomerPullClient.YouzanPulledCustomer newCust = new YouzanCustomerPullClient.YouzanPulledCustomer(
                "YZ-ENRICH-NEW", "大贝壳", "大贝壳", "13911112222",
                "FEMALE", null, Instant.parse("2026-06-01T00:00:00Z"), 1, 3001L, null);
        // 2. 已绑定老客户 → enrichProfile=true 时调用 enrichCustomer 并走非破坏性 backfillMissingProfile
        crmCustomerNoById.put(3002L, "C0000003002");
        linksByOpenId.put("YZ-ENRICH-EXIST", new YouzanCustomerLink(
                2L, SHOP_ID, "YZ-ENRICH-EXIST", 3002L, "STRONG", "HIGH", "seed", 1L, clock.instant(), 0L));
        YouzanCustomerPullClient.YouzanPulledCustomer existingCust = new YouzanCustomerPullClient.YouzanPulledCustomer(
                "YZ-ENRICH-EXIST", "老客户", "老客户", "13911113333",
                "FEMALE", null, Instant.parse("2026-06-02T00:00:00Z"), 1, 3002L, null);

        when(pullClient.pullScrmPage(eq(TENANT_ID), eq(SHOP_ID), any(), any(), eq(1), eq(20)))
                .thenReturn(new YouzanCustomerPullClient.YouzanCustomerPage(
                        List.of(newCust, existingCust), 2, false, List.of()));
        when(pullClient.enrichCustomer(TENANT_ID, SHOP_ID, "YZ-ENRICH-NEW", 1, 3001L, "13911112222"))
                .thenReturn(new YouzanCustomerPullClient.YouzanCustomerEnrichment(
                        "大贝壳", "大贝壳", "13911112222", "FEMALE", java.time.LocalDate.of(1994, 5, 1),
                        "有赞黑金备注", "杭州市", List.of("贵宾客户", "有赞老客"), null));
        when(pullClient.enrichCustomer(TENANT_ID, SHOP_ID, "YZ-ENRICH-EXIST", 1, 3002L, "13911113333"))
                .thenReturn(new YouzanCustomerPullClient.YouzanCustomerEnrichment(
                        "老客户", "老客户", "13911113333", "FEMALE", null,
                        "老客有赞备注", "上海市", List.of("贵宾客户"), null));
        when(importService.resolveOrCreateTagCodes(eq(TENANT_ID), eq(ADMIN), eq(List.of("贵宾客户"))))
                .thenReturn(List.of("VIP"));
        when(importService.resolveOrCreateTagCodes(eq(TENANT_ID), eq(ADMIN), eq(List.of("有赞老客"))))
                .thenReturn(List.of("YZ_TAG_OLD"));
        when(importService.backfillMissingProfile(eq(TENANT_ID), eq(ADMIN), eq(3002L), any()))
                .thenReturn(new CustomerExternalImportService.BackfillProfileResult(
                        true, List.of("城市", "备注", "标签(1)"), 1));

        List<CustomerExternalImportService.ExternalCustomerCommand> capturedCommands = new ArrayList<>();
        when(importService.importCustomer(eq(TENANT_ID), eq(ADMIN), any(), any()))
                .thenAnswer(inv -> {
                    CustomerExternalImportService.ExternalCustomerCommand cmd = inv.getArgument(3);
                    capturedCommands.add(cmd);
                    long id = customerIdSeq.incrementAndGet();
                    String no = String.format("C%010d", id);
                    crmCustomerNoById.put(id, no);
                    return new CustomerExternalImportService.ExternalImportResult(
                            CustomerExternalImportService.OUTCOME_CREATED, id, no, cmd.displayName(),
                            MobileSupport.mask(cmd.mobile()), List.of(), List.of(), "新建 CRM 客户 " + no);
                });

        YouzanCustomerSyncRunResponse run = service.pull(
                ADMIN, pullReq("SCRM_LIST", "BATCH", 10, true, false), "k-enrich-detail");

        assertThat(run.createdCount()).isEqualTo(1);
        assertThat(run.skippedExistingCount()).isEqualTo(1);
        assertThat(capturedCommands).hasSize(1);
        assertThat(capturedCommands.get(0).cityName()).isEqualTo("杭州市");
        assertThat(capturedCommands.get(0).remark()).isEqualTo("有赞黑金备注");
        assertThat(capturedCommands.get(0).tagCodes()).containsExactly("VIP", "YZ_TAG_OLD");
        // 标签「贵宾客户」在同批次内命中缓存，仅解析一次
        verify(importService, times(1)).resolveOrCreateTagCodes(eq(TENANT_ID), eq(ADMIN), eq(List.of("贵宾客户")));
        verify(importService, times(1)).backfillMissingProfile(eq(TENANT_ID), eq(ADMIN), eq(3002L), any());
        var existingItem = run.items().stream()
                .filter(i -> "YZ-ENRICH-EXIST".equals(i.externalCustomerId())).findFirst().orElseThrow();
        assertThat(existingItem.detailMasked()).contains("已补全空缺资料").contains("城市").contains("备注");
    }

    private static YouzanCustomerPullRequest pullReq(String sourceMode, String pullMode, Integer limit,
                                                     boolean enrichProfile, boolean resetCursor) {
        return new YouzanCustomerPullRequest(
                SHOP_ID, sourceMode, pullMode, limit, OWNER_ID, null, null, null, null, enrichProfile, resetCursor);
    }

    private static YouzanCustomerPullClient.YouzanCustomerPage slice(
            List<YouzanCustomerPullClient.YouzanPulledCustomer> all,
            Instant start, Instant end, int pageNo, int pageSize) {
        List<YouzanCustomerPullClient.YouzanPulledCustomer> filtered = all.stream()
                .filter(c -> start == null || c.createdAt() == null || !c.createdAt().isBefore(start))
                .filter(c -> end == null || c.createdAt() == null || !c.createdAt().isAfter(end))
                .toList();
        int from = (pageNo - 1) * pageSize;
        if (from >= filtered.size()) {
            return new YouzanCustomerPullClient.YouzanCustomerPage(List.of(), filtered.size(), false, List.of());
        }
        int to = Math.min(from + pageSize, filtered.size());
        return new YouzanCustomerPullClient.YouzanCustomerPage(
                filtered.subList(from, to), filtered.size(), to < filtered.size(), List.of());
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        private void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class InMemorySyncRepository implements YouzanCustomerSyncRepository {
        private final Map<String, YouzanCustomerSyncCursor> cursors = new HashMap<>();
        private final Map<Long, YouzanCustomerSyncRun> runs = new HashMap<>();
        private final Set<String> activeLocks = new HashSet<>();
        private final List<YouzanCustomerSyncItem> items = new ArrayList<>();
        private final AtomicLong runIdSeq = new AtomicLong(0L);
        private final AtomicLong itemIdSeq = new AtomicLong(0L);

        boolean hasActiveRunningLock(long tenantId, String shopId, String sourceMode) {
            return activeLocks.contains(lockKey(tenantId, shopId, sourceMode));
        }

        @Override
        public Optional<YouzanCustomerSyncCursor> findCursor(long tenantId, String shopId, String sourceMode) {
            return Optional.ofNullable(cursors.get(lockKey(tenantId, shopId, sourceMode)));
        }

        @Override
        public Optional<YouzanCustomerSyncCursor> findCursorForUpdate(long tenantId, String shopId, String sourceMode) {
            return findCursor(tenantId, shopId, sourceMode);
        }

        @Override
        public void upsertCursor(YouzanCustomerSyncCursor cursor) {
            String key = lockKey(cursor.tenantId(), cursor.shopId(), cursor.sourceMode());
            YouzanCustomerSyncCursor prev = cursors.get(key);
            long total = (prev == null ? 0L : prev.totalImportedCount()) + cursor.totalImportedCount();
            Instant lastAnchor = cursor.lastAnchorAt() != null ? cursor.lastAnchorAt()
                    : (prev == null ? null : prev.lastAnchorAt());
            long version = prev == null ? 0L : prev.version() + 1L;
            cursors.put(key, new YouzanCustomerSyncCursor(
                    prev == null ? 1L : prev.id(), cursor.tenantId(), cursor.shopId(), cursor.sourceMode(),
                    cursor.cursorJson(), cursor.hasMore(), lastAnchor, cursor.lastSyncedAt(),
                    total, Instant.now(), Instant.now(), version));
        }

        @Override
        public void resetCursor(long tenantId, String shopId, String sourceMode) {
            cursors.remove(lockKey(tenantId, shopId, sourceMode));
        }

        @Override
        public long tryStartRun(long tenantId, String shopId, String sourceMode, String pullMode,
                                Integer requestedLimit, long ownerStaffId, String adapterName,
                                long createdBy, Instant startedAt, Instant leaseUntil) {
            String key = lockKey(tenantId, shopId, sourceMode);
            if (!activeLocks.add(key)) {
                return 0L;
            }
            long id = runIdSeq.incrementAndGet();
            runs.put(id, new YouzanCustomerSyncRun(
                    id, tenantId, shopId, sourceMode, pullMode, requestedLimit, ownerStaffId, adapterName,
                    "RUNNING", 0, 0, 0, 0, 0, false, null, null, null, leaseUntil, createdBy, startedAt, null));
            return id;
        }

        @Override
        public int finishRun(long tenantId, long runId, String status, int fetchedCount, int createdCount,
                             int skippedExistingCount, int mobileConflictCount, int failedCount, boolean hasMore,
                             String cursorAfterJson, String warningsJson, String errorMasked, Instant finishedAt) {
            YouzanCustomerSyncRun prev = runs.get(runId);
            if (prev == null || prev.tenantId() != tenantId || !"RUNNING".equals(prev.status())) {
                return 0;
            }
            activeLocks.remove(lockKey(tenantId, prev.shopId(), prev.sourceMode()));
            runs.put(runId, new YouzanCustomerSyncRun(
                    prev.id(), prev.tenantId(), prev.shopId(), prev.sourceMode(), prev.pullMode(),
                    prev.requestedLimit(), prev.ownerStaffId(), prev.adapterName(), status,
                    fetchedCount, createdCount, skippedExistingCount, mobileConflictCount, failedCount,
                    hasMore, cursorAfterJson, warningsJson, errorMasked, prev.leaseUntil(),
                    prev.createdBy(), prev.startedAt(), finishedAt));
            return 1;
        }

        @Override
        public Optional<YouzanCustomerSyncRun> findRun(long tenantId, long runId) {
            YouzanCustomerSyncRun run = runs.get(runId);
            return run != null && run.tenantId() == tenantId ? Optional.of(run) : Optional.empty();
        }

        @Override
        public List<YouzanCustomerSyncRun> listRuns(long tenantId, String shopId, String sourceMode,
                                                    int offset, int size) {
            return runs.values().stream()
                    .filter(r -> r.tenantId() == tenantId && r.shopId().equals(shopId)
                            && (sourceMode == null || sourceMode.equals(r.sourceMode())))
                    .sorted(Comparator.comparing(YouzanCustomerSyncRun::id).reversed())
                    .skip(offset).limit(size).toList();
        }

        @Override
        public long countRuns(long tenantId, String shopId, String sourceMode) {
            return runs.values().stream()
                    .filter(r -> r.tenantId() == tenantId && r.shopId().equals(shopId)
                            && (sourceMode == null || sourceMode.equals(r.sourceMode())))
                    .count();
        }

        @Override
        public void insertItem(YouzanCustomerSyncItem item) {
            long id = itemIdSeq.incrementAndGet();
            items.add(new YouzanCustomerSyncItem(
                    id, item.tenantId(), item.runId(), item.shopId(), item.externalCustomerId(),
                    item.outcome(), item.displayNameMasked(), item.mobileMasked(),
                    item.crmCustomerId(), item.crmCustomerNo(),
                    item.conflictCustomerIdsJson(), item.conflictCustomerNosJson(),
                    item.detailMasked(), item.createdAt()));
        }

        @Override
        public List<YouzanCustomerSyncItem> listItems(long tenantId, long runId, String outcome,
                                                      int offset, int size) {
            return items.stream()
                    .filter(i -> i.tenantId() == tenantId && i.runId() == runId
                            && (outcome == null || outcome.equals(i.outcome())))
                    .skip(offset).limit(size).toList();
        }

        @Override
        public long countItems(long tenantId, long runId, String outcome) {
            return items.stream()
                    .filter(i -> i.tenantId() == tenantId && i.runId() == runId
                            && (outcome == null || outcome.equals(i.outcome())))
                    .count();
        }

        private static String lockKey(long tenantId, String shopId, String sourceMode) {
            return tenantId + ":" + shopId + ":" + sourceMode;
        }
    }
}
