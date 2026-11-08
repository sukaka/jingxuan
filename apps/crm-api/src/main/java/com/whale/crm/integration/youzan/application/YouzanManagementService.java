package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.integration.youzan.adapter.RealYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanAdapterRouter;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanPullPage;
import com.whale.crm.integration.youzan.adapter.YouzanSyncCursorValue;
import com.whale.crm.integration.youzan.adapter.YouzanWindowTooLargeException;
import com.whale.crm.integration.youzan.api.dto.YouzanCustomerLinkRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderView;
import com.whale.crm.integration.youzan.api.dto.YouzanReconcileReport;
import com.whale.crm.integration.youzan.api.dto.YouzanSkuMappingRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncResponse;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
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
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.PackageVersionSnapshot;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class YouzanManagementService {
    private static final Duration MAX_WINDOW = Duration.ofDays(90);
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

    public YouzanManagementService(YouzanRepository repository, YouzanConfigRepository configRepository,
                                   YouzanEventService eventService, YouzanAdapterRouter adapterRouter,
                                   OrderApplicationService orderService, CatalogApplicationService catalogService,
                                   CustomerScopeSupport scopeSupport, IdempotencyService idempotencyService,
                                   AuditService auditService, OutboxService outboxService, ObjectMapper objectMapper,
                                   AesGcmCipher cipher, PlatformTransactionManager transactionManager) {
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
    }

    @Transactional(readOnly = true)
    public PageResponse<YouzanOrderView> orders(long tenantId, CurrentUser user, String shopId, int page, int size) {
        int offset = (page - 1) * size;
        DataScope scope = scopeSupport.scope(user);
        boolean linkedOnly = scope.type() != DataScope.ScopeType.ALL;
        List<YouzanOrderView> items = repository.findLatestOrderEvents(tenantId, shopId, linkedOnly, offset, size).stream()
                .map(raw -> toView(tenantId, user, scope, raw, true)).filter(view -> view != null).toList();
        return new PageResponse<>(items, repository.countDistinctExternalOrders(tenantId, shopId, linkedOnly), page, size);
    }

    @Transactional(readOnly = true)
    public YouzanOrderView order(long tenantId, CurrentUser user, long eventId) {
        YouzanEvent raw = repository.findEvent(tenantId, eventId).orElseThrow(this::orderNotFound);
        return toView(tenantId, user, scopeSupport.scope(user), raw, false);
    }

    private YouzanOrderView toView(long tenantId, CurrentUser user, DataScope scope, YouzanEvent raw,
                                   boolean omitUnlinked) {
        YouzanOrderMap map = repository.findOrderMap(tenantId, raw.shopId(), raw.externalOrderNo()).orElse(null);
        OrderFulfillmentView detail = map == null ? null : orderService.detail(tenantId, user, map.crmOrderId());
        YouzanOrderEvent event = readEvent(raw.payloadCipher());
        if (map == null && !rawVisible(tenantId, user, scope, event)) {
            if (omitUnlinked) {
                return null;
            }
            throw orderNotFound();
        }
        String verificationMethod = detail == null ? null : detail.order().payments().stream()
                .map(payment -> payment.verificationMethod()).filter(method -> method != null).findFirst().orElse(null);
        return new YouzanOrderView(map == null ? null : map.id(), raw.shopId(), raw.externalOrderNo(),
                map == null ? null : map.crmOrderId(), raw.processStatus(),
                detail == null ? null : detail.order().order().paidFen(),
                detail == null ? raw.sourceSystem() : detail.order().order().sourceSystem(),
                detail == null ? raw.shopId() : detail.order().order().externalShopId(),
                raw.eventId(), raw.processStatus(), detail == null ? null : detail.order().order().status(),
                event.externalUpdatedAt(), event.amountFen(), verificationMethod,
                user.permissions().contains("integration:youzan:manage") ? raw.payloadMaskedJson() : null, raw.lastError());
    }

    private boolean rawVisible(long tenantId, CurrentUser user, DataScope scope, YouzanOrderEvent event) {
        if (scope.type() == DataScope.ScopeType.ALL) {
            return true;
        }
        if (event.externalCustomerId() == null || event.externalCustomerId().isBlank()) {
            return false;
        }
        YouzanCustomerLink customer = repository.findCustomerLink(tenantId, event.shopId(), event.externalCustomerId())
                .orElse(null);
        if (customer == null) {
            return false;
        }
        scopeSupport.requireVisible(tenantId, customer.customerId(), user);
        return true;
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
            YouzanSyncResponse response = syncOnce(user.tenantId(), request.shopId(), request.cursor(),
                    request.limit() == null ? 50 : request.limit(), job);
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
            YouzanSyncResponse total = new YouzanSyncResponse(0, 0, 0, 0, cursor, false);
            for (int i = 0; i < 10; i++) {
                YouzanSyncResponse current = syncOnce(user.tenantId(), request.shopId(), cursor,
                        request.limit() == null ? 50 : request.limit(), job);
                total = new YouzanSyncResponse(total.received() + current.received(),
                        total.processed() + current.processed(), total.duplicates() + current.duplicates(),
                        total.issues() + current.issues(), current.nextCursor(), current.hasMore());
                cursor = current.nextCursor();
                if (!current.hasMore()) {
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

    public YouzanSyncResponse syncScheduled(long tenantId, String shopId, String cursor, int limit) {
        YouzanSyncJob job = startJob(tenantId, shopId, "INCREMENTAL");
        try {
            YouzanSyncResponse response = syncOnce(tenantId, shopId, cursor, limit, job);
            finishJob(tenantId, job, response, null);
            return response;
        } catch (RuntimeException ex) {
            finishJob(tenantId, job, null, ex.getMessage());
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public YouzanReconcileReport report(long tenantId, String shopId) {
        YouzanSyncCursor cursor = repository.findCursor(tenantId, shopId).orElse(null);
        return new YouzanReconcileReport(shopId, repository.countEvents(tenantId, shopId),
                repository.countOrderMaps(tenantId, shopId), repository.countIssues(tenantId, "OPEN"),
                cursor == null ? null : cursor.lastSyncAt(), repository.countTodayEvents(tenantId, shopId),
                repository.countCampaignOrders(tenantId, shopId));
    }

    @Transactional(readOnly = true)
    public PageResponse<YouzanSyncIssue> issues(long tenantId, String status, int page, int size) {
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.findIssues(tenantId, status, offset, size),
                repository.countIssues(tenantId, status), page, size);
    }

    public YouzanEventResponse retryIssue(CurrentUser user, long issueId) {
        YouzanSyncIssue issue = repository.findIssueForUpdate(tenant(), issueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "同步异常不存在"));
        if (List.of("RESOLVED", "DEAD").contains(issue.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "已解决或终止的异常不能重试");
        }
        repository.updateIssueStatus(tenant(), issue.id(), issue.version(), "RETRYING", issue.attempts() + 1,
                issue.lastError(), issue.nextRetryAt(), null, null);
        YouzanEventResponse result = eventService.process(user.tenantId(), issue.eventId());
        auditService.record(tenant(), user.userId(), "YOUZAN_ISSUE_RETRY", "YOUZAN", Long.toString(issue.eventId()),
                null, result, null);
        return result;
    }

    @Transactional(readOnly = true)
    public List<YouzanSkuMapping> skuMappings(long tenantId, String shopId) {
        return repository.findSkuMappings(tenantId, shopId);
    }

    public YouzanSkuMapping upsertSkuMapping(CurrentUser user, YouzanSkuMappingRequest request, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        String payload = json(request);
        String scope = "youzan.sku.mapping";
        IdempotencyRecord replay = idempotencyService.lookup(tenant(), scope, key, payload).orElse(null);
        if (replay != null) {
            return repository.findSkuMapping(tenant(), request.shopId(), request.externalSkuId()).orElseThrow();
        }
        try {
            return transactionTemplate.execute(status -> {
                PackageVersionSnapshot snapshot = catalogService.snapshot(tenant(), user, request.crmSkuId());
                if (!snapshot.entitlementIssuable()) {
                    throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "SKU权益规则不完整，不能建立有赞映射");
                }
                YouzanSkuMapping existing = repository.findSkuMapping(tenant(), request.shopId(), request.externalSkuId())
                        .orElse(null);
                if (existing == null) {
                    repository.insertSkuMapping(new YouzanSkuMapping(null, request.shopId(), request.externalSkuId(),
                            request.crmSkuId(), "ACTIVE", null, null));
                } else {
                    if (request.version() == null || !existing.version().equals(request.version())) {
                        throw new BusinessException(ErrorCode.CONFLICT, "SKU映射版本已变化");
                    }
                    if (repository.updateSkuMapping(tenant(), existing.id(), request.version(), request.crmSkuId(), "ACTIVE") == 0) {
                        throw new BusinessException(ErrorCode.CONFLICT, "SKU映射版本冲突");
                    }
                }
                YouzanSkuMapping result = repository.findSkuMapping(tenant(), request.shopId(), request.externalSkuId()).orElseThrow();
                idempotencyService.recordSuccess(tenant(), scope, key, "YOUZAN_SKU_MAPPING",
                        result.id().toString(), payload);
                auditService.record(tenant(), user.userId(), "YOUZAN_SKU_MAPPING_SAVE", "YOUZAN", result.id().toString(),
                        existing, result, null);
                return result;
            });
        } catch (DuplicateKeyException ex) {
            return repository.findSkuMapping(tenant(), request.shopId(), request.externalSkuId()).orElseThrow();
        }
    }

    @Transactional(readOnly = true)
    public List<YouzanCustomerLink> customerLinks(long tenantId, String shopId) {
        return repository.findCustomerLinks(tenantId, shopId);
    }

    public YouzanCustomerLink upsertCustomerLink(CurrentUser user, YouzanCustomerLinkRequest request, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        String payload = json(request);
        String scope = "youzan.customer.link";
        IdempotencyRecord replay = idempotencyService.lookup(tenant(), scope, key, payload).orElse(null);
        if (replay != null) {
            return repository.findCustomerLink(tenant(), request.shopId(), request.externalCustomerId()).orElseThrow();
        }
        try {
            return transactionTemplate.execute(status -> {
                scopeSupport.requireVisible(tenant(), request.customerId(), user);
                YouzanCustomerLink existing = repository.findCustomerLink(tenant(), request.shopId(), request.externalCustomerId()).orElse(null);
                if (existing == null) {
                    repository.insertCustomerLink(new YouzanCustomerLink(null, request.shopId(), request.externalCustomerId(),
                            request.customerId(), "MANUAL", "HIGH", request.reason(), user.userId(), null, null));
                } else {
                    if (request.version() == null || !existing.version().equals(request.version())) {
                        throw new BusinessException(ErrorCode.CONFLICT, "客户映射版本已变化");
                    }
                    if (repository.updateCustomerLink(tenant(), existing.id(), request.version(), request.customerId(),
                            request.reason()) == 0) {
                        throw new BusinessException(ErrorCode.CONFLICT, "客户映射版本冲突");
                    }
                }
                YouzanCustomerLink result = repository.findCustomerLink(tenant(), request.shopId(), request.externalCustomerId())
                        .orElseThrow();
                idempotencyService.recordSuccess(tenant(), scope, key, "YOUZAN_CUSTOMER_LINK",
                        result.id().toString(), payload);
                auditService.record(tenant(), user.userId(), "YOUZAN_CUSTOMER_LINK_SAVE", "YOUZAN",
                        result.id().toString(), existing, result, request.reason());
                return result;
            });
        } catch (DuplicateKeyException ex) {
            return repository.findCustomerLink(tenant(), request.shopId(), request.externalCustomerId()).orElseThrow();
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
        if (adapter instanceof RealYouzanAdapter && !hasMore) {
            YouzanSyncCursorValue position = readCursor(nextCursor);
            if (position.windowEnd().isBefore(position.finalEnd())) {
                Instant nextEnd = position.windowEnd().plus(MAX_WINDOW).isBefore(position.finalEnd())
                        ? position.windowEnd().plus(MAX_WINDOW) : position.finalEnd();
                nextCursor = json(new YouzanSyncCursorValue(position.queryType(), position.windowEnd(), nextEnd,
                        position.finalEnd(), 1));
                hasMore = true;
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
            if (latest == null || event.externalUpdatedAt().isAfter(latest)) {
                latest = event.externalUpdatedAt();
            }
        }
        if (job != null && configRepository.acquireShopLease(tenantId, shopId, job.leaseOwner(),
                Instant.now().plusSeconds(300)) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "同步租约已被其他实例接管，请从最新游标重试");
        }
        saveCursor(tenantId, shopId, nextCursor, latest);
        outboxService.append(tenantId, syncCompletedEventId(shopId, nextCursor),
                "youzan.sync.completed.v1", "youzan", shopId,
                Map.of("shopId", shopId, "received", received, "processed", processed, "issues", issues));
        return new YouzanSyncResponse(received, processed, duplicates, issues, nextCursor, hasMore);
    }

    private YouzanSyncResponse splitOversizedWindow(long tenantId, String shopId, String cursor, int limit,
                                                    YouzanSyncJob job) {
        YouzanSyncCursorValue position = readCursor(cursor);
        long millis = Duration.between(position.windowStart(), position.windowEnd()).toMillis();
        if (millis <= 1000) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "有赞订单过密且时间窗无法继续细分，请按订单号补偿");
        }
        Instant midpoint = position.windowStart().plusMillis(millis / 2);
        String firstHalf = json(new YouzanSyncCursorValue(position.queryType(), position.windowStart(), midpoint,
                position.finalEnd(), 1));
        return syncOnce(tenantId, shopId, firstHalf, limit, job);
    }

    static String syncCompletedEventId(String shopId, String cursor) {
        return "youzan.sync.completed.v1:" + shopId + ":" + IdempotencyService.sha256(cursor == null ? "" : cursor);
    }

    private String initialCursor(long tenantId, String shopId) {
        Instant end = Instant.now();
        Instant start = configRepository.findSyncPolicy(tenantId, shopId)
                .map(YouzanSyncPolicy::initialStartTime).orElse(end.minus(Duration.ofDays(7)));
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

    private void saveCursor(long tenantId, String shopId, String nextCursor, Instant latest) {
        transactionTemplate.execute(status -> {
            YouzanSyncCursor existing = repository.findCursorForUpdate(tenantId, shopId).orElse(null);
            YouzanSyncCursorValue position = nextCursor != null && nextCursor.startsWith("{")
                    ? readCursor(nextCursor) : null;
            if (existing == null) {
                repository.insertCursor(tenantId, new YouzanSyncCursor(null, shopId, nextCursor,
                        position == null ? null : position.windowStart(), position == null ? null : position.windowEnd(),
                        Instant.now(), latest, null, null));
            } else if (repository.updateCursor(tenantId, existing.id(), existing.version(), nextCursor, Instant.now(), latest) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "同步游标已变化，请重试");
            }
            return null;
        });
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
            YouzanSyncJob current = configRepository.findSyncJob(tenantId, job.id()).orElseThrow();
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

    private long tenant() {
        return TenantContext.currentTenantId();
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
