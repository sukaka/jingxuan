package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.youzan.adapter.RealYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanAdapterRouter;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEnvelope;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanOrderLine;
import com.whale.crm.integration.youzan.adapter.YouzanPullPage;
import com.whale.crm.integration.youzan.adapter.YouzanSyncCursorValue;
import com.whale.crm.integration.youzan.adapter.YouzanWindowTooLargeException;
import com.whale.crm.integration.youzan.api.dto.YouzanBatchRetryRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanBatchRetryResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanCustomerLinkRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderAssignRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderItemView;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderView;
import com.whale.crm.integration.youzan.api.dto.YouzanPullOrderByTidRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanReconcileReport;
import com.whale.crm.integration.youzan.api.dto.YouzanSkuCandidateView;
import com.whale.crm.integration.youzan.api.dto.YouzanSkuMappingRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncResponse;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanMappingIssueEvent;
import com.whale.crm.integration.youzan.domain.YouzanOrderAssignment;
import com.whale.crm.integration.youzan.domain.YouzanOrderMap;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanSkuMapping;
import com.whale.crm.integration.youzan.domain.YouzanSyncCursor;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
import com.whale.crm.integration.youzan.domain.YouzanSyncJob;
import com.whale.crm.integration.youzan.domain.YouzanSyncPolicy;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.PackageVersionSnapshot;
import com.whale.crm.product.domain.Sku;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import com.whale.crm.trading.domain.OrderItem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class YouzanManagementService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration MAX_WINDOW = Duration.ofDays(90);
    private static final int MAX_SCHEDULED_PAGES = 100;
    private static final int MAX_SKU_CANDIDATE_SCAN_ISSUES = 500;
    private static final int DEFAULT_BATCH_RETRY_LIMIT = 50;
    private static final int MAX_BATCH_RETRY_LIMIT = 100;
    private static final Set<String> VALID_ASSIGNMENT_STATUS_FILTERS = Set.of(
            "ASSIGNED", "UNASSIGNED", "UNMATCHED", "AMBIGUOUS", "NO_ACCOUNT");
    private final YouzanRepository repository;
    private final YouzanConfigRepository configRepository;
    private final YouzanEventService eventService;
    private final YouzanAdapterRouter adapterRouter;
    private final OrderApplicationService orderService;
    private final CatalogApplicationService catalogService;
    private final CustomerScopeSupport scopeSupport;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final AesGcmCipher cipher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final YouzanRiskLinkOperations riskLinkOperations;
    private final YouzanOrderAssignmentService assignmentService;

    public YouzanManagementService(YouzanRepository repository, YouzanConfigRepository configRepository,
                                   YouzanEventService eventService, YouzanAdapterRouter adapterRouter,
                                   OrderApplicationService orderService, CatalogApplicationService catalogService,
                                   CustomerScopeSupport scopeSupport, IdempotencyService idempotencyService,
                                   AuditService auditService, OutboxService outboxService, ObjectMapper objectMapper,
                                   AesGcmCipher cipher, PlatformTransactionManager transactionManager) {
        this(repository, configRepository, eventService, adapterRouter, orderService, catalogService,
                scopeSupport, idempotencyService, auditService, outboxService, objectMapper, cipher,
                transactionManager, Clock.system(BUSINESS_ZONE), YouzanRiskLinkOperations.NOOP, null);
    }

    public YouzanManagementService(YouzanRepository repository, YouzanConfigRepository configRepository,
                                   YouzanEventService eventService, YouzanAdapterRouter adapterRouter,
                                   OrderApplicationService orderService, CatalogApplicationService catalogService,
                                   CustomerScopeSupport scopeSupport, IdempotencyService idempotencyService,
                                   AuditService auditService, OutboxService outboxService, ObjectMapper objectMapper,
                                   AesGcmCipher cipher, PlatformTransactionManager transactionManager,
                                   YouzanRiskLinkOperations riskLinkOperations) {
        this(repository, configRepository, eventService, adapterRouter, orderService, catalogService,
                scopeSupport, idempotencyService, auditService, outboxService, objectMapper, cipher,
                transactionManager, Clock.system(BUSINESS_ZONE), riskLinkOperations, null);
    }

    @Autowired
    public YouzanManagementService(YouzanRepository repository, YouzanConfigRepository configRepository,
                                   YouzanEventService eventService, YouzanAdapterRouter adapterRouter,
                                   OrderApplicationService orderService, CatalogApplicationService catalogService,
                                   CustomerScopeSupport scopeSupport, IdempotencyService idempotencyService,
                                   AuditService auditService, OutboxService outboxService, ObjectMapper objectMapper,
                                   AesGcmCipher cipher, PlatformTransactionManager transactionManager,
                                   YouzanRiskLinkOperations riskLinkOperations,
                                   ObjectProvider<YouzanOrderAssignmentService> assignmentServiceProvider) {
        this(repository, configRepository, eventService, adapterRouter, orderService, catalogService,
                scopeSupport, idempotencyService, auditService, outboxService, objectMapper, cipher,
                transactionManager, Clock.system(BUSINESS_ZONE), riskLinkOperations,
                assignmentServiceProvider == null ? null : assignmentServiceProvider.getIfAvailable());
    }

    public YouzanManagementService(YouzanRepository repository, YouzanConfigRepository configRepository,
                                   YouzanEventService eventService, YouzanAdapterRouter adapterRouter,
                                   OrderApplicationService orderService, CatalogApplicationService catalogService,
                                   CustomerScopeSupport scopeSupport, IdempotencyService idempotencyService,
                                   AuditService auditService, OutboxService outboxService, ObjectMapper objectMapper,
                                   AesGcmCipher cipher, PlatformTransactionManager transactionManager, Clock clock) {
        this(repository, configRepository, eventService, adapterRouter, orderService, catalogService,
                scopeSupport, idempotencyService, auditService, outboxService, objectMapper, cipher,
                transactionManager, clock, YouzanRiskLinkOperations.NOOP, null);
    }

    public YouzanManagementService(YouzanRepository repository, YouzanConfigRepository configRepository,
                                   YouzanEventService eventService, YouzanAdapterRouter adapterRouter,
                                   OrderApplicationService orderService, CatalogApplicationService catalogService,
                                   CustomerScopeSupport scopeSupport, IdempotencyService idempotencyService,
                                   AuditService auditService, OutboxService outboxService, ObjectMapper objectMapper,
                                   AesGcmCipher cipher, PlatformTransactionManager transactionManager, Clock clock,
                                   YouzanRiskLinkOperations riskLinkOperations) {
        this(repository, configRepository, eventService, adapterRouter, orderService, catalogService,
                scopeSupport, idempotencyService, auditService, outboxService, objectMapper, cipher,
                transactionManager, clock, riskLinkOperations, null);
    }

    public YouzanManagementService(YouzanRepository repository, YouzanConfigRepository configRepository,
                                   YouzanEventService eventService, YouzanAdapterRouter adapterRouter,
                                   OrderApplicationService orderService, CatalogApplicationService catalogService,
                                   CustomerScopeSupport scopeSupport, IdempotencyService idempotencyService,
                                   AuditService auditService, OutboxService outboxService, ObjectMapper objectMapper,
                                   AesGcmCipher cipher, PlatformTransactionManager transactionManager, Clock clock,
                                   YouzanRiskLinkOperations riskLinkOperations,
                                   YouzanOrderAssignmentService assignmentService) {
        this.repository = repository;
        this.configRepository = configRepository;
        this.eventService = eventService;
        this.adapterRouter = adapterRouter;
        this.orderService = orderService;
        this.catalogService = catalogService;
        this.scopeSupport = scopeSupport;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.cipher = cipher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock != null ? clock : Clock.system(BUSINESS_ZONE);
        this.riskLinkOperations = riskLinkOperations != null ? riskLinkOperations : YouzanRiskLinkOperations.NOOP;
        this.assignmentService = assignmentService;
    }

    public PageResponse<YouzanOrderView> orders(long tenantId, CurrentUser user, String shopId, int page, int size) {
        return orders(tenantId, user, shopId, null, null, null, page, size);
    }

    public PageResponse<YouzanOrderView> orders(long tenantId, CurrentUser user, String shopId,
                                                Long assignedStewardId, String assignmentStatus, String keyword,
                                                int page, int size) {
        int offset = (page - 1) * size;
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        String cleanStatus = normalizeAssignmentStatusFilter(assignmentStatus);
        String cleanKeyword = normalizeKeywordFilter(keyword);
        CustomerScopeSupport.ScopeQuery scopeQuery = scopeQuery(scope, user);
        boolean filtered = assignedStewardId != null || cleanStatus != null || cleanKeyword != null;
        List<YouzanEvent> rawEvents = filtered
                ? repository.findLatestOrderEventsFiltered(tenantId, shopId, scopeQuery,
                        assignedStewardId, cleanStatus, cleanKeyword, offset, size)
                : repository.findLatestOrderEvents(tenantId, shopId, scopeQuery, offset, size);
        List<YouzanOrderView> items = toViews(tenantId, user, scope, rawEvents);
        long total = filtered
                ? repository.countDistinctExternalOrdersFiltered(tenantId, shopId, scopeQuery,
                        assignedStewardId, cleanStatus, cleanKeyword)
                : repository.countDistinctExternalOrders(tenantId, shopId, scopeQuery);
        return new PageResponse<>(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public YouzanOrderView order(long tenantId, CurrentUser user, long eventId) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            throw orderNotFound();
        }
        YouzanEvent raw = repository.findEvent(tenantId, eventId).orElseThrow(this::orderNotFound);
        YouzanOrderAssignment assignment = loadSingleAssignment(tenantId, raw);
        Map<Long, UserRepository.StewardOptionRow> staffProfiles = loadStaffProfilesForAssignments(
                tenantId, assignment == null ? List.of() : List.of(assignment));
        PreparedOrderView prepared = prepareOrderView(tenantId, user, scope, raw, false, assignment, staffProfiles);
        return enrichOrderViews(tenantId, user, List.of(prepared), staffProfiles).get(0);
    }

    public YouzanOrderView pullOrderByTid(CurrentUser user, YouzanPullOrderByTidRequest request,
                                          String idempotencyKey) {
        if (user == null || user.isInstitutionUser() || !"EMPLOYEE".equals(user.userType())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅内部员工可执行有赞单笔订单拉取");
        }
        requireKey(idempotencyKey);
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        String shopId = firstNonBlank(request.shopId());
        String tid = firstNonBlank(request.tid());
        if (shopId == null || tid == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "店铺编号和有赞订单号不能为空");
        }
        long tenantId = user.tenantId();
        var adapter = adapterRouter.adapter(tenantId, shopId);
        YouzanOrderEnvelope envelope = adapter.fetchOrder(tenantId, shopId, null, tid);
        YouzanOrderEvent event = envelope.event();
        String providerPayload = envelope.providerPayload() != null ? envelope.providerPayload() : json(event);
        YouzanEventResponse ingested = eventService.ingest(tenantId, event, "PULL_DETAIL", providerPayload);
        YouzanEvent persisted = repository.findEventByKey(tenantId, event.shopId(), event.eventId())
                .orElseGet(() -> ingested != null && ingested.id() != null
                        ? repository.findEvent(tenantId, ingested.id()).orElseThrow() : null);
        if (persisted == null || persisted.id() == null) {
            throw new IllegalStateException("有赞订单事件落库失败");
        }
        eventService.process(tenantId, persisted.id());
        if (assignmentService != null) {
            assignmentService.syncFromProviderPayload(tenantId, event.shopId(), event.externalOrderNo(),
                    persisted.id(), providerPayload);
        }
        YouzanOrderView view = order(tenantId, user, persisted.id());
        Map<String, Object> auditAfter = new LinkedHashMap<>();
        auditAfter.put("shopId", view.shopId());
        auditAfter.put("externalOrderNo", view.externalOrderNo());
        auditAfter.put("eventId", view.eventId());
        auditAfter.put("mappingStatus", view.mappingStatus());
        auditAfter.put("assignmentStatus", view.assignmentStatus());
        auditAfter.put("assignedStewardId", view.assignedStewardId());
        auditService.record(tenantId, user.userId(), AuditService.ACTION_YOUZAN_ORDER_PULL_BY_TID,
                "YOUZAN_ORDER", view.shopId() + ":" + view.externalOrderNo(), null, auditAfter, null);
        return view;
    }

    public YouzanOrderView assignOrderSteward(CurrentUser user, String shopId, String externalOrderNo,
                                              YouzanOrderAssignRequest request, String idempotencyKey) {
        if (user == null || user.isInstitutionUser() || !"EMPLOYEE".equals(user.userType())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅内部员工可执行有赞订单管家分配");
        }
        if (assignmentService == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "有赞订单分配服务未启用");
        }
        String cleanShopId = firstNonBlank(shopId);
        String cleanOrderNo = firstNonBlank(externalOrderNo);
        if (cleanShopId == null || cleanOrderNo == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "店铺编号和有赞订单号不能为空");
        }
        long tenantId = user.tenantId();
        YouzanEvent latest = repository.findLatestEventByExternalOrder(tenantId, cleanShopId, cleanOrderNo)
                .orElseThrow(this::orderNotFound);
        if (!user.permissions().contains("integration:youzan:manage")) {
            order(tenantId, user, latest.id());
        }
        assignmentService.assignOrderSteward(user, cleanShopId, cleanOrderNo, latest.id(), request, idempotencyKey);
        return order(tenantId, user, latest.id());
    }

    private List<YouzanOrderView> toViews(long tenantId, CurrentUser user, DataScope scope,
                                          List<YouzanEvent> rawEvents) {
        if (rawEvents == null || rawEvents.isEmpty()) {
            return List.of();
        }
        Map<String, YouzanOrderAssignment> assignmentsByKey = loadAssignmentsForEvents(tenantId, rawEvents);
        Map<Long, UserRepository.StewardOptionRow> staffProfiles = loadStaffProfilesForAssignments(
                tenantId, assignmentsByKey.values());
        List<PreparedOrderView> prepared = new ArrayList<>(rawEvents.size());
        for (YouzanEvent raw : rawEvents) {
            YouzanOrderAssignment assignment = assignmentsByKey.get(assignmentKey(raw.shopId(), raw.externalOrderNo()));
            PreparedOrderView item = prepareOrderView(tenantId, user, scope, raw, true, assignment, staffProfiles);
            if (item != null) {
                prepared.add(item);
            }
        }
        if (prepared.isEmpty()) {
            return List.of();
        }
        return enrichOrderViews(tenantId, user, prepared, staffProfiles);
    }

    private PreparedOrderView prepareOrderView(long tenantId, CurrentUser user, DataScope scope, YouzanEvent raw,
                                               boolean omitUnlinked, YouzanOrderAssignment assignment,
                                               Map<Long, UserRepository.StewardOptionRow> staffProfiles) {
        YouzanOrderMap map = repository.findOrderMap(tenantId, raw.shopId(), raw.externalOrderNo()).orElse(null);
        OrderFulfillmentView detail = null;
        boolean assignedVisible = isAssignedVisible(scope, user, assignment, staffProfiles);
        if (map != null) {
            try {
                detail = orderService.detail(tenantId, user, map.crmOrderId());
            } catch (BusinessException ex) {
                if (ex.errorCode() == ErrorCode.NOT_FOUND) {
                    if (scope.type() != DataScope.ScopeType.ALL && assignedVisible
                            && repository.isMappedCustomerActive(tenantId, map.crmOrderId())) {
                        // 有赞订单显式分配给当前管家/团队，但内部 CRM 客户归属他人：允许查看有赞外部订单视图，不透出他人内部 CRM 订单明细
                        map = null;
                    } else {
                        if (omitUnlinked) {
                            return null;
                        }
                        throw orderNotFound();
                    }
                } else {
                    throw ex;
                }
            }
        }
        YouzanOrderEvent event = readEvent(raw.payloadCipher());
        if (map == null && !assignedVisible && !rawVisible(tenantId, user, scope, event)) {
            if (omitUnlinked) {
                return null;
            }
            throw orderNotFound();
        }
        return new PreparedOrderView(raw, map, detail, event, assignment);
    }

    private List<YouzanOrderView> enrichOrderViews(long tenantId, CurrentUser user,
                                                   List<PreparedOrderView> preparedOrders,
                                                   Map<Long, UserRepository.StewardOptionRow> staffProfiles) {
        Map<String, Map<String, YouzanSkuMapping>> mappingsByShop = new HashMap<>();
        List<List<OrderItemDraft>> draftsByOrder = new ArrayList<>(preparedOrders.size());
        Set<Long> skuIdsNeedingName = new LinkedHashSet<>();
        for (PreparedOrderView prepared : preparedOrders) {
            Map<String, YouzanSkuMapping> activeMappings = Map.of();
            String rawShopId = prepared.raw().shopId();
            if (hasOrderLines(prepared.event()) && !user.isInstitutionUser()
                    && rawShopId != null && !rawShopId.isBlank()) {
                activeMappings = mappingsByShop.computeIfAbsent(rawShopId,
                        shop -> activeSkuMappingMap(tenantId, shop));
            }
            List<OrderItemDraft> drafts = buildOrderItemDrafts(prepared, activeMappings);
            draftsByOrder.add(drafts);
            for (OrderItemDraft draft : drafts) {
                if (draft.crmSkuId() != null && draft.crmSkuName() == null) {
                    skuIdsNeedingName.add(draft.crmSkuId());
                }
            }
        }
        Map<Long, String> catalogSkuNames = resolveCatalogSkuNames(tenantId, user, skuIdsNeedingName);
        boolean canViewMaskedPayload = user.permissions().contains("integration:youzan:manage");
        List<YouzanOrderView> views = new ArrayList<>(preparedOrders.size());
        for (int i = 0; i < preparedOrders.size(); i++) {
            PreparedOrderView prepared = preparedOrders.get(i);
            List<YouzanOrderItemView> itemViews = draftsByOrder.get(i).stream()
                    .map(draft -> draft.toView(catalogSkuNames))
                    .toList();
            views.add(buildOrderView(prepared, itemViews, canViewMaskedPayload, staffProfiles));
        }
        return views;
    }

    private YouzanOrderView buildOrderView(PreparedOrderView prepared, List<YouzanOrderItemView> itemViews,
                                           boolean canViewMaskedPayload,
                                           Map<Long, UserRepository.StewardOptionRow> staffProfiles) {
        YouzanEvent raw = prepared.raw();
        YouzanOrderMap map = prepared.map();
        OrderFulfillmentView detail = prepared.detail();
        YouzanOrderEvent event = prepared.event();
        YouzanOrderAssignment assignment = prepared.assignment();
        String verificationMethod = detail == null ? null : detail.order().payments().stream()
                .map(payment -> payment.verificationMethod()).filter(method -> method != null).findFirst().orElse(null);
        String assignedStewardName = null;
        if (assignment != null && assignment.assignedStewardId() != null && staffProfiles != null) {
            UserRepository.StewardOptionRow profile = staffProfiles.get(assignment.assignedStewardId());
            if (profile != null) {
                assignedStewardName = profile.displayName();
            }
        }
        return new YouzanOrderView(
                map == null ? null : map.id(),
                raw.shopId(),
                raw.externalOrderNo(),
                map == null ? null : map.crmOrderId(),
                raw.processStatus(),
                detail == null ? null : detail.order().order().paidFen(),
                detail == null ? raw.sourceSystem() : detail.order().order().sourceSystem(),
                detail == null ? raw.shopId() : detail.order().order().externalShopId(),
                raw.eventId(),
                raw.processStatus(),
                detail == null ? null : detail.order().order().status(),
                event.externalUpdatedAt(),
                event.amountFen(),
                verificationMethod,
                canViewMaskedPayload ? raw.payloadMaskedJson() : null,
                raw.lastError(),
                event.externalCustomerId(),
                event.paidAt(),
                event.orderTypeCode(),
                event.campaignId(),
                event.campaignBatchId(),
                itemViews,
                assignment == null ? event.status() : firstNonBlank(assignment.providerOrderStatus(), event.status()),
                assignment == null ? null : assignment.providerStatusText(),
                assignment == null ? null : assignment.payWayDesc(),
                assignment == null ? null : assignment.orderedAt(),
                assignment == null ? null : assignment.consignedAt(),
                assignment == null ? null : assignment.buyerFormNameMasked(),
                assignment == null ? null : assignment.buyerFormMobileMasked(),
                assignment == null ? null : assignment.tradeMemoMasked(),
                assignment == null ? null : assignment.memoPerformanceOwner(),
                assignment == null ? null : assignment.memoChannel(),
                assignment == null ? null : assignment.memoCustomerType(),
                assignment == null ? null : assignment.memoSource(),
                assignment == null ? null : assignment.memoAccountName(),
                assignment == null ? null : assignment.memoInstitutionName(),
                assignment == null ? null : assignment.memoExtraNote(),
                assignment == null ? null : assignment.assignedStewardId(),
                assignedStewardName,
                assignment == null ? null : assignment.assignmentStatus(),
                assignment == null ? null : assignment.assignmentSource(),
                assignment == null ? null : assignment.assignmentReason(),
                assignment == null ? null : assignment.assignedAt(),
                assignment == null ? null : assignment.version());
    }

    private YouzanOrderAssignment loadSingleAssignment(long tenantId, YouzanEvent raw) {
        if (assignmentService == null || raw == null) {
            return null;
        }
        return assignmentService.findByOrder(tenantId, raw.shopId(), raw.externalOrderNo()).orElse(null);
    }

    private Map<String, YouzanOrderAssignment> loadAssignmentsForEvents(long tenantId, List<YouzanEvent> rawEvents) {
        if (assignmentService == null || rawEvents == null || rawEvents.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> ordersByShop = new LinkedHashMap<>();
        for (YouzanEvent raw : rawEvents) {
            if (raw == null || raw.shopId() == null || raw.shopId().isBlank()
                    || raw.externalOrderNo() == null || raw.externalOrderNo().isBlank()) {
                continue;
            }
            ordersByShop.computeIfAbsent(raw.shopId(), k -> new LinkedHashSet<>()).add(raw.externalOrderNo());
        }
        Map<String, YouzanOrderAssignment> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : ordersByShop.entrySet()) {
            Map<String, YouzanOrderAssignment> shopMap = assignmentService.findByOrdersMap(
                    tenantId, entry.getKey(), entry.getValue());
            for (Map.Entry<String, YouzanOrderAssignment> orderEntry : shopMap.entrySet()) {
                result.put(assignmentKey(entry.getKey(), orderEntry.getKey()), orderEntry.getValue());
            }
        }
        return result;
    }

    private Map<Long, UserRepository.StewardOptionRow> loadStaffProfilesForAssignments(
            long tenantId, java.util.Collection<YouzanOrderAssignment> assignments) {
        if (assignmentService == null || assignments == null || assignments.isEmpty()) {
            return Map.of();
        }
        List<Long> stewardIds = assignments.stream()
                .filter(Objects::nonNull)
                .map(YouzanOrderAssignment::assignedStewardId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (stewardIds.isEmpty()) {
            return Map.of();
        }
        return assignmentService.loadStaffProfiles(tenantId, stewardIds);
    }

    private static boolean isAssignedVisible(DataScope scope, CurrentUser user, YouzanOrderAssignment assignment,
                                             Map<Long, UserRepository.StewardOptionRow> staffProfiles) {
        if (assignment == null || assignment.assignedStewardId() == null || scope == null || user == null) {
            return false;
        }
        return switch (scope.type()) {
            case ALL -> true;
            case SELF -> Objects.equals(assignment.assignedStewardId(), user.userId());
            case TEAM -> {
                if (Objects.equals(assignment.assignedStewardId(), user.userId())) {
                    yield true;
                }
                UserRepository.StewardOptionRow profile = staffProfiles == null
                        ? null : staffProfiles.get(assignment.assignedStewardId());
                Long teamId = profile != null && profile.teamId() != null
                        ? profile.teamId() : assignment.assignedTeamId();
                yield teamId != null && scope.teamIds() != null && scope.teamIds().contains(teamId);
            }
            default -> false;
        };
    }

    private static String assignmentKey(String shopId, String externalOrderNo) {
        return (shopId == null ? "" : shopId) + "\u0000" + (externalOrderNo == null ? "" : externalOrderNo);
    }

    private static String normalizeAssignmentStatusFilter(String raw) {
        String clean = firstNonBlank(raw);
        if (clean == null) {
            return null;
        }
        String upper = clean.toUpperCase(java.util.Locale.ROOT);
        if (!VALID_ASSIGNMENT_STATUS_FILTERS.contains(upper)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "无效的管家分配状态筛选值");
        }
        return upper;
    }

    private static String normalizeKeywordFilter(String raw) {
        String clean = firstNonBlank(raw);
        return clean == null ? null : truncate(clean, 100);
    }

    private List<OrderItemDraft> buildOrderItemDrafts(PreparedOrderView prepared,
                                                      Map<String, YouzanSkuMapping> activeMappings) {
        List<YouzanOrderLine> rawLines = prepared.event() == null ? null : prepared.event().items();
        if (rawLines == null || rawLines.isEmpty()) {
            return List.of();
        }
        List<YouzanOrderLine> lines = rawLines.stream().filter(Objects::nonNull).toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        List<ExtractedLineMeta> extractedLines = extractProductLineMetas(
                prepared.raw().providerPayloadMaskedJson(), prepared.raw().payloadMaskedJson());
        boolean[] usedMeta = new boolean[extractedLines.size()];
        Map<String, ProductLineMeta> mergedMetaBySku = mergeProductLineMetaBySku(extractedLines);

        List<OrderItem> internalItems = extractInternalOrderItems(prepared.detail());
        boolean[] usedInternal = new boolean[internalItems.size()];

        List<OrderItemDraft> drafts = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            YouzanOrderLine line = lines.get(i);
            String externalSkuId = line.externalSkuId();
            String normalizedSkuId = externalSkuId == null || externalSkuId.isBlank() ? null : externalSkuId.strip();
            ProductLineMeta meta = matchLineMeta(i, normalizedSkuId, extractedLines, usedMeta, mergedMetaBySku);
            YouzanSkuMapping activeMapping = normalizedSkuId == null ? null : activeMappings.get(normalizedSkuId);
            Long mappedSkuId = activeMapping == null ? null : activeMapping.crmSkuId();
            OrderItem internalItem = matchInternalOrderItem(i, lines.size(), mappedSkuId, internalItems, usedInternal);
            Long crmSkuId = internalItem != null && internalItem.skuId() != null ? internalItem.skuId() : mappedSkuId;
            String crmSkuName = internalItem == null ? null : firstNonBlank(internalItem.skuNameSnapshot());
            drafts.add(new OrderItemDraft(
                    externalSkuId,
                    line.quantity(),
                    meta == null ? null : meta.itemTitle(),
                    meta == null ? null : meta.skuSpec(),
                    meta == null ? null : meta.priceFen(),
                    meta == null ? null : meta.paymentFen(),
                    meta == null ? null : meta.outerSkuCode(),
                    crmSkuId,
                    crmSkuName
            ));
        }
        return drafts;
    }

    private Map<Long, String> resolveCatalogSkuNames(long tenantId, CurrentUser user, Set<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<Sku> skus = catalogService.skusByIds(tenantId, user, skuIds);
            if (skus == null || skus.isEmpty()) {
                return Map.of();
            }
            Map<Long, String> result = new HashMap<>();
            for (Sku sku : skus) {
                if (sku == null || sku.id() == null) {
                    continue;
                }
                String displayName = formatCrmSkuName(sku);
                if (displayName != null) {
                    result.put(sku.id(), displayName);
                }
            }
            return result;
        } catch (BusinessException | AccessDeniedException ex) {
            return Map.of();
        }
    }

    private static boolean hasOrderLines(YouzanOrderEvent event) {
        return event != null && event.items() != null && !event.items().isEmpty();
    }

    private static List<OrderItem> extractInternalOrderItems(OrderFulfillmentView detail) {
        if (detail == null || detail.order() == null || detail.order().items() == null) {
            return List.of();
        }
        return detail.order().items().stream().filter(Objects::nonNull).toList();
    }

    private static ProductLineMeta matchLineMeta(int index, String normalizedSkuId,
                                                 List<ExtractedLineMeta> extractedLines, boolean[] usedMeta,
                                                 Map<String, ProductLineMeta> mergedMetaBySku) {
        if (normalizedSkuId == null) {
            return null;
        }
        if (index < extractedLines.size() && !usedMeta[index]
                && normalizedSkuId.equals(extractedLines.get(index).externalSkuId())) {
            usedMeta[index] = true;
            return extractedLines.get(index).meta();
        }
        for (int j = 0; j < extractedLines.size(); j++) {
            if (!usedMeta[j] && normalizedSkuId.equals(extractedLines.get(j).externalSkuId())) {
                usedMeta[j] = true;
                return extractedLines.get(j).meta();
            }
        }
        return mergedMetaBySku.get(normalizedSkuId);
    }

    private static OrderItem matchInternalOrderItem(int index, int totalLines, Long mappedSkuId,
                                                    List<OrderItem> internalItems, boolean[] usedInternal) {
        if (internalItems.isEmpty()) {
            return null;
        }
        if (mappedSkuId != null) {
            if (index < internalItems.size() && !usedInternal[index]
                    && mappedSkuId.equals(internalItems.get(index).skuId())) {
                usedInternal[index] = true;
                return internalItems.get(index);
            }
            for (int j = 0; j < internalItems.size(); j++) {
                if (!usedInternal[j] && mappedSkuId.equals(internalItems.get(j).skuId())) {
                    usedInternal[j] = true;
                    return internalItems.get(j);
                }
            }
        }
        if (internalItems.size() == totalLines && index < internalItems.size() && !usedInternal[index]) {
            usedInternal[index] = true;
            return internalItems.get(index);
        }
        return null;
    }

    private static String formatCrmSkuName(Sku sku) {
        String packageName = firstNonBlank(sku.packageName());
        String specName = firstNonBlank(sku.specName());
        if (packageName != null && specName != null) {
            return packageName + "｜" + specName;
        }
        if (packageName != null) {
            return packageName;
        }
        if (specName != null) {
            return specName;
        }
        return firstNonBlank(sku.skuCode());
    }

    private boolean rawVisible(long tenantId, CurrentUser user, DataScope scope, YouzanOrderEvent event) {
        if (scope.type() == DataScope.ScopeType.ALL) {
            return true;
        }
        if (scopeSupport.isEmptyScope(scope) || event.externalCustomerId() == null || event.externalCustomerId().isBlank()) {
            return false;
        }
        YouzanCustomerLink customer = repository.findCustomerLink(tenantId, event.shopId(), event.externalCustomerId())
                .orElse(null);
        if (customer == null) {
            return false;
        }
        try {
            scopeSupport.requireVisible(tenantId, customer.customerId(), user);
            return true;
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.NOT_FOUND) {
                return false;
            }
            throw ex;
        }
    }

    private CustomerScopeSupport.ScopeQuery scopeQuery(DataScope scope, CurrentUser user) {
        return Optional.ofNullable(scopeSupport.queryOf(scope, user))
                .orElseGet(() -> {
                    List<Long> teamIds = scope.teamIds() == null
                            ? null : scope.teamIds().stream().sorted().toList();
                    return new CustomerScopeSupport.ScopeQuery(scope.type().name(), teamIds, user.userId());
                });
    }

    private YouzanOrderEvent readEvent(String encrypted) {
        try {
            return objectMapper.readValue(cipher.decrypt(encrypted), YouzanOrderEvent.class);
        } catch (Exception ex) {
            throw new IllegalStateException("有赞原始事件解析失败", ex);
        }
    }

    private BusinessException orderNotFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不在数据范围内");
    }

    public YouzanSyncResponse sync(CurrentUser user, YouzanSyncRequest request, String idempotencyKey) {
        requireKey(idempotencyKey);
        YouzanSyncJob job = startJob(user.tenantId(), request.shopId(), "FULL");
        try {
            int pageLimit = resolveSyncPageLimit(request.limit(), request.maxOrders());
            YouzanSyncResponse response = syncOnce(user.tenantId(), request.shopId(), request.cursor(),
                    pageLimit, job);
            finishJob(user.tenantId(), job, response, null);
            auditService.record(user.tenantId(), user.userId(), "YOUZAN_SYNC", "YOUZAN", request.shopId(), null, response, null);
            return response;
        } catch (RuntimeException ex) {
            finishJob(user.tenantId(), job, null, ex.getMessage());
            throw ex;
        }
    }

    public YouzanSyncResponse reconcile(CurrentUser user, YouzanSyncRequest request, String idempotencyKey) {
        requireKey(idempotencyKey);
        YouzanSyncJob job = startJob(user.tenantId(), request.shopId(), "RECONCILE");
        try {
            String cursor = request.cursor();
            Integer maxOrders = request.maxOrders();
            int pageLimit = resolveReconcilePageLimit(request.limit(), maxOrders);
            YouzanSyncResponse total = new YouzanSyncResponse(0, 0, 0, 0, cursor, false);
            for (int i = 0; i < 10; i++) {
                if (maxOrders != null) {
                    int remaining = maxOrders - total.received();
                    if (remaining <= 0 || (total.received() > 0 && remaining < pageLimit)) {
                        break;
                    }
                }
                YouzanSyncResponse current = syncOnce(user.tenantId(), request.shopId(), cursor, pageLimit, job);
                total = new YouzanSyncResponse(total.received() + current.received(),
                        total.processed() + current.processed(), total.duplicates() + current.duplicates(),
                        total.issues() + current.issues(), current.nextCursor(), current.hasMore());
                cursor = current.nextCursor();
                if (!current.hasMore()) {
                    break;
                }
                if (maxOrders != null && total.received() >= maxOrders) {
                    break;
                }
            }
            finishJob(user.tenantId(), job, total, null);
            auditService.record(user.tenantId(), user.userId(), "YOUZAN_RECONCILE", "YOUZAN", request.shopId(), null, total, null);
            return total;
        } catch (RuntimeException ex) {
            finishJob(user.tenantId(), job, null, ex.getMessage());
            throw ex;
        }
    }

    static int resolveSyncPageLimit(Integer requestedLimit, Integer maxOrders) {
        if (maxOrders == null) {
            return requestedLimit == null ? 50 : requestedLimit;
        }
        return requestedLimit == null ? Math.min(maxOrders, 100) : Math.min(requestedLimit, maxOrders);
    }

    static int resolveReconcilePageLimit(Integer requestedLimit, Integer maxOrders) {
        if (maxOrders == null) {
            return requestedLimit == null ? 50 : requestedLimit;
        }
        if (requestedLimit != null) {
            return Math.min(requestedLimit, maxOrders);
        }
        if (maxOrders <= 100) {
            return maxOrders;
        }
        for (int candidate = 100; candidate >= 1; candidate--) {
            if (maxOrders % candidate == 0 && maxOrders / candidate <= 10) {
                return candidate;
            }
        }
        return 50;
    }

    public YouzanSyncResponse syncScheduled(long tenantId, String shopId, String cursor, int limit) {
        YouzanSyncJob job = startJob(tenantId, shopId, "INCREMENTAL");
        try {
            String nextCursor = cursor;
            YouzanSyncResponse total = new YouzanSyncResponse(0, 0, 0, 0, nextCursor, false);
            for (int i = 0; i < MAX_SCHEDULED_PAGES; i++) {
                YouzanSyncResponse current = syncOnce(tenantId, shopId, nextCursor, limit, job);
                total = new YouzanSyncResponse(total.received() + current.received(),
                        total.processed() + current.processed(), total.duplicates() + current.duplicates(),
                        total.issues() + current.issues(), current.nextCursor(), current.hasMore());
                nextCursor = current.nextCursor();
                if (!current.hasMore()) {
                    break;
                }
            }
            finishJob(tenantId, job, total, null);
            return total;
        } catch (RuntimeException ex) {
            finishJob(tenantId, job, null, ex.getMessage());
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public YouzanReconcileReport report(long tenantId, CurrentUser user, String shopId) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new YouzanReconcileReport(shopId, 0, 0, 0, null, 0, 0);
        }
        YouzanSyncCursor cursor = repository.findCursor(tenantId, shopId).orElse(null);
        LocalDate businessToday = clock.instant().atZone(BUSINESS_ZONE).toLocalDate();
        Instant todayStartUtc = businessToday.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant todayEndUtcExclusive = businessToday.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        if (scope.type() == DataScope.ScopeType.ALL || user.permissions().contains("integration:youzan:manage")) {
            return new YouzanReconcileReport(shopId, repository.countEvents(tenantId, shopId),
                    repository.countOrderMaps(tenantId, shopId), repository.countIssues(tenantId, "OPEN"),
                    cursor == null ? null : cursor.lastSyncAt(),
                    repository.countTodayEvents(tenantId, shopId, todayStartUtc, todayEndUtcExclusive),
                    repository.countCampaignOrders(tenantId, shopId));
        }
        CustomerScopeSupport.ScopeQuery scopeQuery = scopeQuery(scope, user);
        return new YouzanReconcileReport(shopId,
                repository.countScopedEvents(tenantId, shopId, scopeQuery),
                repository.countScopedOrderMaps(tenantId, shopId, scopeQuery),
                repository.countScopedIssues(tenantId, "OPEN", scopeQuery),
                cursor == null ? null : cursor.lastSyncAt(),
                repository.countScopedTodayEvents(tenantId, shopId, scopeQuery, todayStartUtc, todayEndUtcExclusive),
                repository.countScopedCampaignOrders(tenantId, shopId, scopeQuery));
    }

    @Transactional(readOnly = true)
    public PageResponse<YouzanSyncIssue> issues(long tenantId, String status, int page, int size) {
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.findIssues(tenantId, status, offset, size),
                repository.countIssues(tenantId, status), page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<YouzanSyncIssue> issues(long tenantId, CurrentUser user, String status, int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        if (scope.type() == DataScope.ScopeType.ALL || user.permissions().contains("integration:youzan:manage")) {
            return issues(tenantId, status, page, size);
        }
        int offset = (page - 1) * size;
        CustomerScopeSupport.ScopeQuery scopeQuery = scopeQuery(scope, user);
        return new PageResponse<>(repository.findScopedIssues(tenantId, status, scopeQuery, offset, size),
                repository.countScopedIssues(tenantId, status, scopeQuery), page, size);
    }

    public YouzanEventResponse retryIssue(CurrentUser user, long issueId) {
        long tenantId = user.tenantId();
        if (!user.permissions().contains("integration:youzan:manage")) {
            DataScope scope = scopeSupport.scope(user);
            if (scopeSupport.isEmptyScope(scope)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "同步异常不存在");
            }
            if (scope.type() != DataScope.ScopeType.ALL) {
                YouzanSyncIssue existing = repository.findIssue(tenantId, issueId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "同步异常不存在"));
                YouzanEvent raw = repository.findEvent(tenantId, existing.eventId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "同步异常不存在"));
                YouzanOrderAssignment assignment = loadSingleAssignment(tenantId, raw);
                Map<Long, UserRepository.StewardOptionRow> profiles = loadStaffProfilesForAssignments(
                        tenantId, assignment == null ? List.of() : List.of(assignment));
                if (prepareOrderView(tenantId, user, scope, raw, true, assignment, profiles) == null) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "同步异常不存在");
                }
            }
        }
        YouzanSyncIssue issue = Objects.requireNonNull(transactionTemplate.execute(status -> {
            YouzanSyncIssue locked = repository.findIssueForUpdate(tenantId, issueId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "同步异常不存在"));
            if (List.of("RESOLVED", "DEAD").contains(locked.status())) {
                throw new BusinessException(ErrorCode.CONFLICT, "已解决或终止的异常不能重试");
            }
            if (repository.updateIssueStatus(tenantId, locked.id(), locked.version(), "RETRYING",
                    locked.attempts() + 1, locked.lastError(), locked.nextRetryAt(), null, null) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "同步异常状态已变化，请重试");
            }
            return locked;
        }));
        YouzanEventResponse result = eventService.process(tenantId, issue.eventId());
        auditService.record(tenantId, user.userId(), "YOUZAN_ISSUE_RETRY", "YOUZAN", Long.toString(issue.eventId()),
                null, result, null);
        return result;
    }

    @Transactional(readOnly = true)
    public List<YouzanSkuMapping> skuMappings(long tenantId, CurrentUser user, String shopId) {
        if (user.isInstitutionUser()) {
            return List.of();
        }
        return repository.findSkuMappings(tenantId, shopId);
    }

    @Transactional(readOnly = true)
    public List<YouzanSkuCandidateView> skuCandidates(long tenantId, CurrentUser user, String shopId) {
        String normalizedShopId = shopId == null ? "" : shopId.strip();
        if (normalizedShopId.isEmpty() || user.isInstitutionUser()) {
            return List.of();
        }
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return List.of();
        }
        CustomerScopeSupport.ScopeQuery scopeQuery = (scope.type() == DataScope.ScopeType.ALL
                || user.permissions().contains("integration:youzan:manage"))
                ? null : scopeQuery(scope, user);
        Map<String, YouzanSkuMapping> activeMappings = activeSkuMappingMap(tenantId, normalizedShopId);
        List<YouzanMappingIssueEvent> rows = repository.findOpenMappingIssueEvents(
                tenantId, normalizedShopId, scopeQuery, MAX_SKU_CANDIDATE_SCAN_ISSUES);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, SkuCandidateAccumulator> accumulators = new LinkedHashMap<>();
        for (YouzanMappingIssueEvent row : rows) {
            EventSkuInspection inspection = inspectEventSkus(row);
            List<String> eventSkuIds = inspection.validExternalSkuIds();
            if (eventSkuIds.isEmpty()) {
                continue;
            }
            boolean allMapped = inspection.allMapped(activeMappings);
            Map<String, ProductLineMeta> metaBySku = extractProductMetadata(row);
            for (String externalSkuId : eventSkuIds) {
                YouzanSkuMapping activeMapping = activeMappings.get(externalSkuId);
                boolean mapped = activeMapping != null;
                if (mapped && !allMapped) {
                    continue;
                }
                SkuCandidateAccumulator acc = accumulators.computeIfAbsent(externalSkuId,
                        key -> new SkuCandidateAccumulator(normalizedShopId, key, mapped,
                                activeMapping == null ? null : activeMapping.crmSkuId()));
                acc.record(row, metaBySku.get(externalSkuId));
            }
        }
        return accumulators.values().stream()
                .map(SkuCandidateAccumulator::toView)
                .sorted(Comparator
                        .comparing(YouzanSkuCandidateView::mapped)
                        .thenComparing(Comparator.comparingInt(YouzanSkuCandidateView::pendingIssueCount).reversed())
                        .thenComparing(YouzanSkuCandidateView::latestEventAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(YouzanSkuCandidateView::externalSkuId))
                .toList();
    }

    public YouzanBatchRetryResponse batchRetryMappingIssues(CurrentUser user, YouzanBatchRetryRequest request,
                                                            String idempotencyKey) {
        requireKey(idempotencyKey);
        long tenantId = user.tenantId();
        String shopId = request.shopId() == null ? "" : request.shopId().strip();
        if (shopId.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "店铺编号不能为空");
        }
        String targetExternalSkuId = request.externalSkuId() == null || request.externalSkuId().isBlank()
                ? null : request.externalSkuId().strip();
        int maxToAttempt = request.limit() == null ? DEFAULT_BATCH_RETRY_LIMIT
                : Math.min(MAX_BATCH_RETRY_LIMIT, Math.max(1, request.limit()));
        if (user.isInstitutionUser()) {
            return new YouzanBatchRetryResponse(shopId, targetExternalSkuId, 0, 0, 0, 0, 0, 0, 0);
        }
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new YouzanBatchRetryResponse(shopId, targetExternalSkuId, 0, 0, 0, 0, 0, 0, 0);
        }
        Map<String, YouzanSkuMapping> activeMappings = activeSkuMappingMap(tenantId, shopId);
        if (targetExternalSkuId != null && !activeMappings.containsKey(targetExternalSkuId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该外部SKU尚未建立有效映射，请先保存映射后再批量重试");
        }
        CustomerScopeSupport.ScopeQuery scopeQuery = (scope.type() == DataScope.ScopeType.ALL
                || user.permissions().contains("integration:youzan:manage"))
                ? null : scopeQuery(scope, user);
        List<YouzanMappingIssueEvent> rows = repository.findOpenMappingIssueEvents(
                tenantId, shopId, scopeQuery, MAX_SKU_CANDIDATE_SCAN_ISSUES);
        List<Long> matchedIssueIds = new ArrayList<>();
        if (rows != null) {
            for (YouzanMappingIssueEvent row : rows) {
                if (row.issueId() == null) {
                    continue;
                }
                EventSkuInspection inspection = inspectEventSkus(row);
                if (!inspection.allMapped(activeMappings)) {
                    continue;
                }
                if (targetExternalSkuId != null && !inspection.validExternalSkuIds().contains(targetExternalSkuId)) {
                    continue;
                }
                matchedIssueIds.add(row.issueId());
            }
        }
        int matched = matchedIssueIds.size();
        int batchCount = Math.min(matched, maxToAttempt);
        int remaining = Math.max(0, matched - batchCount);
        int attempted = 0;
        int processed = 0;
        int advanced = 0;
        int stillFailed = 0;
        int skipped = 0;
        for (int i = 0; i < batchCount; i++) {
            long issueId = matchedIssueIds.get(i);
            try {
                YouzanEventResponse res = retryIssue(user, issueId);
                attempted++;
                if ("PROCESSED".equals(res.processStatus())) {
                    processed++;
                } else if ("CUSTOMER_UNRESOLVED".equals(res.processStatus())
                        || "PAYMENT_MISMATCH".equals(res.processStatus())) {
                    advanced++;
                } else {
                    stillFailed++;
                }
            } catch (BusinessException ex) {
                if (ex.errorCode() == ErrorCode.CONFLICT || ex.errorCode() == ErrorCode.NOT_FOUND) {
                    skipped++;
                } else {
                    throw ex;
                }
            }
        }
        YouzanBatchRetryResponse summary = new YouzanBatchRetryResponse(
                shopId, targetExternalSkuId, matched, attempted, processed, advanced, stillFailed, skipped, remaining);
        auditService.record(tenantId, user.userId(), "YOUZAN_ISSUE_BATCH_RETRY", "YOUZAN", shopId,
                null, summary, null);
        return summary;
    }

    private Map<String, YouzanSkuMapping> activeSkuMappingMap(long tenantId, String shopId) {
        List<YouzanSkuMapping> mappings = repository.findSkuMappings(tenantId, shopId);
        Map<String, YouzanSkuMapping> result = new HashMap<>();
        if (mappings != null) {
            for (YouzanSkuMapping mapping : mappings) {
                if (mapping != null && "ACTIVE".equals(mapping.status())
                        && mapping.externalSkuId() != null && !mapping.externalSkuId().isBlank()) {
                    result.put(mapping.externalSkuId().strip(), mapping);
                }
            }
        }
        return result;
    }

    private EventSkuInspection inspectEventSkus(YouzanMappingIssueEvent row) {
        if (row == null || row.payloadCipher() == null || row.payloadCipher().isBlank()) {
            return EventSkuInspection.INVALID;
        }
        YouzanOrderEvent event;
        try {
            event = readEvent(row.payloadCipher());
        } catch (IllegalStateException ex) {
            return EventSkuInspection.INVALID;
        }
        if (event == null || event.items() == null || event.items().isEmpty()) {
            return EventSkuInspection.INVALID;
        }
        Set<String> skuIds = new LinkedHashSet<>();
        boolean hasInvalidLine = false;
        for (YouzanOrderLine line : event.items()) {
            if (line == null || line.externalSkuId() == null || line.externalSkuId().isBlank()
                    || line.quantity() == null || line.quantity() <= 0) {
                hasInvalidLine = true;
                continue;
            }
            skuIds.add(line.externalSkuId().strip());
        }
        return new EventSkuInspection(List.copyOf(skuIds), hasInvalidLine);
    }

    private Map<String, ProductLineMeta> extractProductMetadata(YouzanMappingIssueEvent row) {
        return mergeProductLineMetaBySku(
                extractProductLineMetas(row.providerPayloadMaskedJson(), row.payloadMaskedJson()));
    }

    private List<ExtractedLineMeta> extractProductLineMetas(String providerPayloadMaskedJson,
                                                            String payloadMaskedJson) {
        List<ExtractedLineMeta> providerLines = extractProductLinesFromJson(providerPayloadMaskedJson);
        if (hasAnyLineMetadata(providerLines)) {
            return providerLines;
        }
        List<ExtractedLineMeta> payloadLines = extractProductLinesFromJson(payloadMaskedJson);
        if (hasAnyLineMetadata(payloadLines)) {
            return payloadLines;
        }
        return !providerLines.isEmpty() ? providerLines : payloadLines;
    }

    private List<ExtractedLineMeta> extractProductLinesFromJson(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(jsonText);
            JsonNode lines = resolveOrderLinesNode(root);
            if (lines == null || !lines.isArray() || lines.isEmpty()) {
                return List.of();
            }
            List<ExtractedLineMeta> result = new ArrayList<>();
            for (JsonNode item : lines) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String externalSkuId = resolveExternalSkuId(item);
                if (externalSkuId == null) {
                    continue;
                }
                String title = truncate(firstNonBlank(jsonText(item, "title"),
                        jsonText(item, "item_title"), jsonText(item, "goods_title")), 200);
                String skuSpec = truncate(parseSkuSpec(item.path("sku_properties_name"), item.path("sku_spec")), 200);
                Long priceFen = parsePriceFen(item);
                Long paymentFen = parsePaymentFen(item);
                String outerSkuCode = truncate(firstNonBlank(jsonText(item, "outer_sku_id"),
                        jsonText(item, "outer_item_id")), 128);
                result.add(new ExtractedLineMeta(externalSkuId,
                        new ProductLineMeta(title, skuSpec, priceFen, paymentFen, outerSkuCode)));
            }
            return result;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private static Map<String, ProductLineMeta> mergeProductLineMetaBySku(List<ExtractedLineMeta> lines) {
        if (lines == null || lines.isEmpty()) {
            return Map.of();
        }
        Map<String, ProductLineMeta> result = new HashMap<>();
        for (ExtractedLineMeta line : lines) {
            if (line != null && line.externalSkuId() != null && line.meta() != null) {
                result.merge(line.externalSkuId(), line.meta(), ProductLineMeta::merge);
            }
        }
        return result;
    }

    private static boolean hasAnyLineMetadata(List<ExtractedLineMeta> lines) {
        for (ExtractedLineMeta line : lines) {
            if (line != null && line.meta() != null && line.meta().hasAnyMetadata()) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode resolveOrderLinesNode(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode[] candidates = new JsonNode[] {
                root.path("data").path("full_order_info").path("orders"),
                root.path("full_order_info").path("orders"),
                root.path("orders"),
                root.path("items")
        };
        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isArray() && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private static String resolveExternalSkuId(JsonNode item) {
        String itemId = jsonText(item, "item_id");
        if (itemId != null) {
            String skuId = jsonText(item, "sku_id");
            return itemId + ":" + (skuId == null ? "0" : skuId);
        }
        return jsonText(item, "external_sku_id");
    }

    private String parseSkuSpec(JsonNode propertiesNode, JsonNode fallbackSpecNode) {
        String parsed = parseSkuPropertiesNode(propertiesNode);
        if (parsed != null) {
            return parsed;
        }
        return parseSkuPropertiesNode(fallbackSpecNode);
    }

    private String parseSkuPropertiesNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            return formatSkuPropertyArray(node);
        }
        if (node.isTextual()) {
            String text = node.asText().strip();
            if (text.isEmpty() || "[]".equals(text)) {
                return null;
            }
            if (text.startsWith("[")) {
                try {
                    JsonNode array = objectMapper.readTree(text);
                    if (array.isArray()) {
                        return formatSkuPropertyArray(array);
                    }
                } catch (JsonProcessingException ignored) {
                    // 回退按原字符串展示
                }
            }
            return text;
        }
        return null;
    }

    private static String formatSkuPropertyArray(JsonNode array) {
        List<String> parts = new ArrayList<>();
        for (JsonNode entry : array) {
            if (entry == null || entry.isNull()) {
                continue;
            }
            if (entry.isObject()) {
                String k = firstNonBlank(jsonText(entry, "k"), jsonText(entry, "key"), jsonText(entry, "name"));
                String v = firstNonBlank(jsonText(entry, "v"), jsonText(entry, "value"));
                if (k != null && v != null) {
                    parts.add(k + "：" + v);
                } else if (v != null) {
                    parts.add(v);
                } else if (k != null) {
                    parts.add(k);
                }
            } else if (entry.isValueNode()) {
                String val = entry.asText().strip();
                if (!val.isEmpty()) {
                    parts.add(val);
                }
            }
        }
        return parts.isEmpty() ? null : String.join(" / ", parts);
    }

    private static Long parsePriceFen(JsonNode item) {
        JsonNode fenNode = item.path("price_fen");
        if (fenNode.isIntegralNumber()) {
            if (!fenNode.canConvertToLong()) {
                return null;
            }
            long value = fenNode.asLong();
            return value >= 0 ? value : null;
        }
        Long price = parseYuanToFen(jsonText(item, "price"));
        if (price != null) {
            return price;
        }
        return parseYuanToFen(jsonText(item, "discount_price"));
    }

    private static Long parsePaymentFen(JsonNode item) {
        JsonNode fenNode = item.path("payment_fen");
        if (fenNode.isIntegralNumber()) {
            if (!fenNode.canConvertToLong()) {
                return null;
            }
            long value = fenNode.asLong();
            return value >= 0 ? value : null;
        }
        Long payment = parseYuanToFen(jsonText(item, "payment"));
        if (payment != null) {
            return payment;
        }
        return parseYuanToFen(jsonText(item, "total_fee"));
    }

    private static Long parseYuanToFen(String yuanText) {
        if (yuanText == null) {
            return null;
        }
        try {
            BigDecimal fen = new BigDecimal(yuanText).movePointRight(2).setScale(0, RoundingMode.HALF_UP);
            long value = fen.longValueExact();
            return value >= 0 ? value : null;
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    private static String jsonText(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull() || !child.isValueNode()) {
            return null;
        }
        String value = child.asText().strip();
        return value.isEmpty() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
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

    private record PreparedOrderView(YouzanEvent raw, YouzanOrderMap map, OrderFulfillmentView detail,
                                     YouzanOrderEvent event, YouzanOrderAssignment assignment) {
    }

    private record OrderItemDraft(String externalSkuId, Integer quantity, String itemTitle, String skuSpec,
                                  Long priceFen, Long paymentFen, String outerSkuCode, Long crmSkuId,
                                  String crmSkuName) {
        private YouzanOrderItemView toView(Map<Long, String> catalogSkuNames) {
            String resolvedName = crmSkuName != null ? crmSkuName
                    : (crmSkuId == null ? null : catalogSkuNames.get(crmSkuId));
            return new YouzanOrderItemView(externalSkuId, quantity, itemTitle, skuSpec, priceFen, paymentFen,
                    outerSkuCode, crmSkuId, resolvedName);
        }
    }

    private record ExtractedLineMeta(String externalSkuId, ProductLineMeta meta) {
    }

    private record EventSkuInspection(List<String> validExternalSkuIds, boolean hasInvalidLine) {
        private static final EventSkuInspection INVALID = new EventSkuInspection(List.of(), true);

        private boolean allMapped(Map<String, YouzanSkuMapping> activeMappings) {
            return !hasInvalidLine && !validExternalSkuIds.isEmpty()
                    && validExternalSkuIds.stream().allMatch(activeMappings::containsKey);
        }
    }

    private record ProductLineMeta(String itemTitle, String skuSpec, Long priceFen, Long paymentFen,
                                   String outerSkuCode) {
        private boolean hasAnyMetadata() {
            return itemTitle != null || skuSpec != null || priceFen != null
                    || paymentFen != null || outerSkuCode != null;
        }

        private ProductLineMeta merge(ProductLineMeta other) {
            if (other == null) {
                return this;
            }
            return new ProductLineMeta(
                    itemTitle != null ? itemTitle : other.itemTitle,
                    skuSpec != null ? skuSpec : other.skuSpec,
                    priceFen != null ? priceFen : other.priceFen,
                    paymentFen != null ? paymentFen : other.paymentFen,
                    outerSkuCode != null ? outerSkuCode : other.outerSkuCode
            );
        }
    }

    private static final class SkuCandidateAccumulator {
        private final String shopId;
        private final String externalSkuId;
        private final boolean mapped;
        private final Long crmSkuId;
        private final Set<Long> issueIds = new LinkedHashSet<>();
        private String itemTitle;
        private String skuSpec;
        private Long priceFen;
        private String outerSkuCode;
        private String sampleOrderNo;
        private Instant latestEventAt;

        private SkuCandidateAccumulator(String shopId, String externalSkuId, boolean mapped, Long crmSkuId) {
            this.shopId = shopId;
            this.externalSkuId = externalSkuId;
            this.mapped = mapped;
            this.crmSkuId = crmSkuId;
        }

        private void record(YouzanMappingIssueEvent row, ProductLineMeta meta) {
            if (row.issueId() != null) {
                issueIds.add(row.issueId());
            }
            if (meta != null) {
                if (itemTitle == null && meta.itemTitle() != null) {
                    itemTitle = meta.itemTitle();
                }
                if (skuSpec == null && meta.skuSpec() != null) {
                    skuSpec = meta.skuSpec();
                }
                if (priceFen == null && meta.priceFen() != null) {
                    priceFen = meta.priceFen();
                }
                if (outerSkuCode == null && meta.outerSkuCode() != null) {
                    outerSkuCode = meta.outerSkuCode();
                }
            }
            if (row.receivedAt() != null && (latestEventAt == null || row.receivedAt().isAfter(latestEventAt))) {
                latestEventAt = row.receivedAt();
                if (row.externalOrderNo() != null && !row.externalOrderNo().isBlank()) {
                    sampleOrderNo = row.externalOrderNo();
                }
            } else if (sampleOrderNo == null && row.externalOrderNo() != null && !row.externalOrderNo().isBlank()) {
                sampleOrderNo = row.externalOrderNo();
            }
        }

        private YouzanSkuCandidateView toView() {
            return new YouzanSkuCandidateView(
                    shopId, externalSkuId, itemTitle, skuSpec, priceFen, outerSkuCode,
                    issueIds.size(), sampleOrderNo, latestEventAt, mapped, crmSkuId);
        }
    }

    public YouzanSkuMapping upsertSkuMapping(CurrentUser user, YouzanSkuMappingRequest request, String idempotencyKey) {
        long tenantId = user.tenantId();
        String key = requireKey(idempotencyKey);
        String payload = json(request);
        String scope = "youzan.sku.mapping";
        IdempotencyRecord replay = idempotencyService.lookup(tenantId, scope, key, payload).orElse(null);
        if (replay != null) {
            return repository.findSkuMapping(tenantId, request.shopId(), request.externalSkuId()).orElseThrow();
        }
        try {
            return transactionTemplate.execute(status -> {
                PackageVersionSnapshot snapshot = catalogService.snapshot(tenantId, user, request.crmSkuId());
                if (!snapshot.entitlementIssuable()) {
                    throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "SKU权益规则不完整，不能建立有赞映射");
                }
                YouzanSkuMapping existing = repository.findSkuMapping(tenantId, request.shopId(), request.externalSkuId())
                        .orElse(null);
                if (existing == null) {
                    repository.insertSkuMapping(tenantId, new YouzanSkuMapping(null, request.shopId(), request.externalSkuId(),
                            request.crmSkuId(), "ACTIVE", null, null));
                } else {
                    if (request.version() == null || !existing.version().equals(request.version())) {
                        throw new BusinessException(ErrorCode.CONFLICT, "SKU映射版本已变化");
                    }
                    if (repository.updateSkuMapping(tenantId, existing.id(), request.version(), request.crmSkuId(), "ACTIVE") == 0) {
                        throw new BusinessException(ErrorCode.CONFLICT, "SKU映射版本冲突");
                    }
                }
                YouzanSkuMapping result = repository.findSkuMapping(tenantId, request.shopId(), request.externalSkuId()).orElseThrow();
                idempotencyService.recordSuccess(tenantId, scope, key, "YOUZAN_SKU_MAPPING",
                        result.id().toString(), payload);
                auditService.record(tenantId, user.userId(), "YOUZAN_SKU_MAPPING_SAVE", "YOUZAN", result.id().toString(),
                        existing, result, null);
                return result;
            });
        } catch (DuplicateKeyException ex) {
            return repository.findSkuMapping(tenantId, request.shopId(), request.externalSkuId()).orElseThrow();
        }
    }

    @Transactional(readOnly = true)
    public List<YouzanCustomerLink> customerLinks(long tenantId, CurrentUser user, String shopId) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return List.of();
        }
        if (scope.type() == DataScope.ScopeType.ALL || user.permissions().contains("integration:youzan:manage")) {
            return repository.findCustomerLinks(tenantId, shopId);
        }
        return repository.findScopedCustomerLinks(tenantId, shopId, scopeQuery(scope, user));
    }

    public YouzanCustomerLink upsertCustomerLink(CurrentUser user, YouzanCustomerLinkRequest request, String idempotencyKey) {
        long tenantId = user.tenantId();
        String key = requireKey(idempotencyKey);
        String payload = json(request);
        String scope = "youzan.customer.link";
        IdempotencyRecord replay = idempotencyService.lookup(tenantId, scope, key, payload).orElse(null);
        if (replay != null) {
            return repository.findCustomerLink(tenantId, request.shopId(), request.externalCustomerId()).orElseThrow();
        }
        try {
            return transactionTemplate.execute(status -> {
                scopeSupport.requireVisible(tenantId, request.customerId(), user);
                YouzanCustomerLink existing = repository.findCustomerLink(tenantId, request.shopId(), request.externalCustomerId()).orElse(null);
                if (existing == null) {
                    repository.insertCustomerLink(tenantId, new YouzanCustomerLink(null, request.shopId(), request.externalCustomerId(),
                            request.customerId(), "MANUAL", "HIGH", request.reason(), user.userId(), null, null));
                } else {
                    if (request.version() == null || !existing.version().equals(request.version())) {
                        throw new BusinessException(ErrorCode.CONFLICT, "客户映射版本已变化");
                    }
                    if (repository.updateCustomerLink(tenantId, existing.id(), request.version(), request.customerId(),
                            request.reason()) == 0) {
                        throw new BusinessException(ErrorCode.CONFLICT, "客户映射版本冲突");
                    }
                }
                YouzanCustomerLink result = repository.findCustomerLink(tenantId, request.shopId(), request.externalCustomerId())
                        .orElseThrow();
                List<Long> issueIds = repository.findIssueIdsWithoutOrderByExternalCustomer(
                        tenantId, request.shopId(), request.externalCustomerId());
                if (issueIds != null && !issueIds.isEmpty()) {
                    riskLinkOperations.linkCustomerToYouzanIssues(tenantId,
                            issueIds.stream().map(Object::toString).toList(), result.customerId());
                }
                idempotencyService.recordSuccess(tenantId, scope, key, "YOUZAN_CUSTOMER_LINK",
                        result.id().toString(), payload);
                auditService.record(tenantId, user.userId(), "YOUZAN_CUSTOMER_LINK_SAVE", "YOUZAN",
                        result.id().toString(), existing, result, request.reason());
                return result;
            });
        } catch (DuplicateKeyException ex) {
            return repository.findCustomerLink(tenantId, request.shopId(), request.externalCustomerId()).orElseThrow();
        }
    }

    private YouzanSyncResponse syncOnce(long tenantId, String shopId, String cursor, int limit, YouzanSyncJob job) {
        var adapter = adapterRouter.adapter(tenantId, shopId);
        String effectiveCursor = cursor;
        if (adapter instanceof RealYouzanAdapter && (effectiveCursor == null || effectiveCursor.isBlank())) {
            effectiveCursor = initialCursor(tenantId, shopId);
        }
        YouzanPullPage page;
        try {
            page = adapter.pullOrders(tenantId, shopId, effectiveCursor, limit);
        } catch (YouzanWindowTooLargeException ex) {
            return splitOversizedWindow(tenantId, shopId, effectiveCursor, limit, job);
        }
        String nextCursor = page.nextCursor();
        boolean hasMore = page.hasMore();
        boolean advancedWindow = false;
        if (adapter instanceof RealYouzanAdapter && !hasMore) {
            YouzanSyncCursorValue position = readCursor(nextCursor);
            if (position.windowEnd().isBefore(position.finalEnd())) {
                Instant nextEnd = position.windowEnd().plus(MAX_WINDOW).isBefore(position.finalEnd())
                        ? position.windowEnd().plus(MAX_WINDOW) : position.finalEnd();
                nextCursor = json(new YouzanSyncCursorValue(position.queryType(), position.windowEnd(), nextEnd,
                        position.finalEnd(), 1));
                hasMore = true;
                advancedWindow = true;
            }
        }
        int received = 0;
        int processed = 0;
        int duplicates = 0;
        int issues = 0;
        Instant latest = null;
        for (YouzanOrderEvent event : page.events()) {
            String raw = page.rawPayloads().getOrDefault(event.eventId(), json(event));
            eventService.ingest(tenantId, event, "PULL", raw);
            YouzanEventResponse result = eventService.process(tenantId, repository.findEventByKey(tenantId, shopId, event.eventId())
                    .orElseThrow().id());
            received++;
            if (result.duplicate()) {
                duplicates++;
            }
            if ("PROCESSED".equals(result.processStatus())) {
                processed++;
            } else {
                issues++;
            }
            if (event.externalUpdatedAt() != null && (latest == null || event.externalUpdatedAt().isAfter(latest))) {
                latest = event.externalUpdatedAt();
            }
        }
        if (job != null) {
            Instant leaseExpiresAt = Instant.now().plusSeconds(300);
            if (configRepository.acquireShopLease(tenantId, shopId, job.leaseOwner(), leaseExpiresAt) != 1
                    || (job.id() != null && configRepository.renewSyncJobLease(tenantId, job.id(),
                    job.leaseOwner(), leaseExpiresAt) != 1)) {
                throw new BusinessException(ErrorCode.CONFLICT, "同步租约已被其他实例接管或已过期，请从最新游标重试");
            }
        }
        saveCursor(tenantId, shopId, nextCursor, latest, hasMore, advancedWindow);
        outboxService.append(tenantId, syncCompletedEventId(shopId, nextCursor),
                "youzan.sync.completed.v1", "youzan", shopId,
                Map.of("shopId", shopId, "received", received, "processed", processed, "issues", issues));
        return new YouzanSyncResponse(received, processed, duplicates, issues, nextCursor, hasMore);
    }

    private YouzanSyncResponse splitOversizedWindow(long tenantId, String shopId, String cursor, int limit,
                                                    YouzanSyncJob job) {
        YouzanSyncCursorValue position = readCursor(cursor);
        long seconds = Duration.between(position.windowStart(), position.windowEnd()).getSeconds();
        if (seconds <= 1) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "有赞订单过密且时间窗无法继续细分，请按订单号补偿");
        }
        Instant midpoint = position.windowStart().plusSeconds(seconds / 2);
        String firstHalf = json(new YouzanSyncCursorValue(position.queryType(), position.windowStart(), midpoint,
                position.finalEnd(), 1));
        return syncOnce(tenantId, shopId, firstHalf, limit, job);
    }

    static String syncCompletedEventId(String shopId, String cursor) {
        return "youzan.sync.completed.v1:" + shopId + ":" + IdempotencyService.sha256(cursor == null ? "" : cursor);
    }

    private String initialCursor(long tenantId, String shopId) {
        Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start = configRepository.findSyncPolicy(tenantId, shopId)
                .map(YouzanSyncPolicy::initialStartTime).orElse(end.minus(Duration.ofDays(7)))
                .truncatedTo(ChronoUnit.SECONDS);
        Instant windowEnd = start.plus(MAX_WINDOW).isBefore(end) ? start.plus(MAX_WINDOW) : end;
        return json(new YouzanSyncCursorValue("CREATED", start, windowEnd, end, 1));
    }

    private YouzanSyncCursorValue readCursor(String value) {
        try {
            return objectMapper.readValue(value, YouzanSyncCursorValue.class);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞同步游标无效");
        }
    }

    private void saveCursor(long tenantId, String shopId, String nextCursor, Instant latest,
                            boolean hasMore, boolean advancedWindow) {
        transactionTemplate.execute(status -> {
            YouzanSyncCursor existing = repository.findCursorForUpdate(tenantId, shopId).orElse(null);
            YouzanSyncCursorValue position = nextCursor != null && nextCursor.startsWith("{")
                    ? readCursor(nextCursor) : null;
            Instant windowStart = position != null ? position.windowStart()
                    : (existing == null ? null : existing.windowStart());
            Instant windowEnd = position != null ? position.windowEnd()
                    : (existing == null ? null : existing.windowEnd());
            Instant lastSyncAt = resolveLastSyncAt(existing, position, hasMore, advancedWindow);
            Instant lastExternalUpdatedAt = resolveLastExternalUpdatedAt(existing, latest);
            if (existing == null) {
                repository.insertCursor(tenantId, new YouzanSyncCursor(null, shopId, nextCursor,
                        windowStart, windowEnd, lastSyncAt, lastExternalUpdatedAt, null, null));
            } else if (repository.updateCursor(tenantId, existing.id(), existing.version(), nextCursor,
                    windowStart, windowEnd, lastSyncAt, lastExternalUpdatedAt) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "同步游标已变化，请重试");
            }
            return null;
        });
    }

    private Instant resolveLastSyncAt(YouzanSyncCursor existing, YouzanSyncCursorValue position,
                                      boolean hasMore, boolean advancedWindow) {
        if (position == null) {
            return Instant.now();
        }
        Instant existingLastSyncAt = existing == null ? null : existing.lastSyncAt();
        if (!hasMore) {
            Instant candidate = position.windowEnd() != null ? position.windowEnd() : Instant.now();
            return existingLastSyncAt != null && existingLastSyncAt.isAfter(candidate) ? existingLastSyncAt : candidate;
        }
        if (advancedWindow && position.pageNo() == 1 && position.windowStart() != null) {
            return existingLastSyncAt != null && existingLastSyncAt.isAfter(position.windowStart())
                    ? existingLastSyncAt : position.windowStart();
        }
        if (existingLastSyncAt != null) {
            return existingLastSyncAt;
        }
        return position.windowStart() != null ? position.windowStart() : Instant.now();
    }

    private Instant resolveLastExternalUpdatedAt(YouzanSyncCursor existing, Instant latest) {
        Instant existingUpdatedAt = existing == null ? null : existing.lastExternalUpdatedAt();
        if (latest == null) {
            return existingUpdatedAt;
        }
        if (existingUpdatedAt != null && existingUpdatedAt.isAfter(latest)) {
            return existingUpdatedAt;
        }
        return latest;
    }

    private YouzanSyncJob startJob(long tenantId, String shopId, String type) {
        if (configRepository.findShopConfig(tenantId, shopId).isEmpty()) {
            return null;
        }
        String owner = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(300);
        if (configRepository.acquireShopLease(tenantId, shopId, owner, expiresAt) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "该店铺已有同步任务执行中，请稍后重试");
        }
        try {
            YouzanSyncJob job = new YouzanSyncJob(null, shopId, type, "RUNNING", null, null, null,
                    0, 0, 0, 0, null, owner, expiresAt, null, Instant.now(), null, null);
            configRepository.insertSyncJob(tenantId, job);
            return configRepository.findSyncJobByLeaseOwner(tenantId, owner).orElseThrow();
        } catch (RuntimeException ex) {
            configRepository.releaseShopLease(tenantId, shopId, owner);
            throw ex;
        }
    }

    private void finishJob(long tenantId, YouzanSyncJob job, YouzanSyncResponse response, String error) {
        if (job == null || job.id() == null) {
            return;
        }
        try {
            YouzanSyncJob current = configRepository.findSyncJob(tenantId, job.id()).orElse(null);
            if (current == null || !"RUNNING".equals(current.status())
                    || !Objects.equals(current.leaseOwner(), job.leaseOwner())) {
                return;
            }
            String status = error != null ? "FAILED" : response != null && response.issues() > 0 ? "PARTIAL" : "SUCCEEDED";
            String errorMessage = error == null || error.isBlank() ? "有赞同步失败" : error;
            YouzanSyncJob finished = new YouzanSyncJob(current.id(), current.shopId(), current.jobType(), status,
                    response == null ? current.cursorValue() : response.nextCursor(), current.windowStart(), current.windowEnd(),
                    response == null ? current.receivedCount() : response.received(),
                    response == null ? current.processedCount() : response.processed(),
                    response == null ? current.duplicateCount() : response.duplicates(),
                    response == null ? current.issueCount() : response.issues(),
                    error == null ? null : errorMessage.substring(0, Math.min(errorMessage.length(), 1000)), null, null,
                    current.createdAt(), current.startedAt(), Instant.now(), current.version());
            configRepository.updateSyncJob(tenantId, current.id(), current.version(), finished);
        } finally {
            configRepository.releaseShopLease(tenantId, job.shopId(), job.leaseOwner());
        }
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供1至128字符的幂等键");
        }
        return key.strip();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("有赞请求序列化失败", ex);
        }
    }
}
