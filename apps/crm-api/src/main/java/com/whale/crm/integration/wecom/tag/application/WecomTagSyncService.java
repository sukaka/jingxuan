package com.whale.crm.integration.wecom.tag.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerApplicationService;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.application.CustomerTagAdminService;
import com.whale.crm.customer.application.CustomerTagSyncPort;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.tag.adapter.WecomCorpTag;
import com.whale.crm.integration.wecom.tag.adapter.WecomTagClient;
import com.whale.crm.integration.wecom.tag.adapter.WecomTagClientRouter;
import com.whale.crm.integration.wecom.tag.api.dto.UpdateWecomTagMappingRequest;
import com.whale.crm.integration.wecom.tag.api.dto.WecomCustomerTagPushResponse;
import com.whale.crm.integration.wecom.tag.api.dto.WecomTagMappingItemResponse;
import com.whale.crm.integration.wecom.tag.api.dto.WecomTagMappingOverviewResponse;
import com.whale.crm.integration.wecom.tag.api.dto.WecomTagPushTaskResponse;
import com.whale.crm.integration.wecom.tag.api.dto.WecomTagSyncRunResponse;
import com.whale.crm.integration.wecom.tag.domain.WecomCustomerTagPushTask;
import com.whale.crm.integration.wecom.tag.domain.WecomLinkedContactForTagSync;
import com.whale.crm.integration.wecom.tag.domain.WecomTagMapping;
import com.whale.crm.integration.wecom.tag.domain.WecomTagSyncRepository;
import com.whale.crm.integration.wecom.tag.domain.WecomTagSyncRun;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.api.dto.DictionaryItemResponse;
import com.whale.crm.platform.dictionary.api.dto.UpdateDictionaryItemRequest;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 企业微信企业标签双向同步应用服务（仅官方企业标签 API / 本地开发 Mock，严禁个人微信）。
 * 1. 标签库双向同步通过 DictionaryApplicationService 维护 customerTags，不跨模块偷查 Mapper；
 * 2. CRM → 企微客户标签变更在本地事务内仅入队 push_task，绝不因外部通道失败回滚本地写；
 * 3. 企微 → CRM 客户标签回流仅更新已映射标签子集、保留未映射本地标签，且不反向触发入队（零回声循环）。
 */
@Service
public class WecomTagSyncService implements CustomerTagSyncPort {

    public static final String IDEMPOTENCY_SCOPE_CATALOG = "wecom:tag:catalog_sync";
    public static final String IDEMPOTENCY_SCOPE_CUSTOMERS = "wecom:tag:customer_sync";
    public static final String IDEMPOTENCY_SCOPE_CUSTOMER_PUSH = "wecom:tag:customer_push";
    public static final String STATUS_UNMAPPED = "UNMAPPED";

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final long DEFAULT_RUN_LEASE_SECONDS = 300L;
    private static final long DEFAULT_TASK_LEASE_SECONDS = 60L;
    private static final int DEFAULT_RECENT_RUN_LIMIT = 50;
    private static final int DEFAULT_TASK_LIST_LIMIT = 100;
    private static final int SCOPED_TASK_FETCH_LIMIT = 500;
    private static final int DEFAULT_WORKER_BATCH_SIZE = 50;
    private static final Set<String> VALID_DIRECTIONS = Set.of(
            WecomTagSyncRun.DIRECTION_PULL,
            WecomTagSyncRun.DIRECTION_PUSH,
            WecomTagSyncRun.DIRECTION_BIDIRECTIONAL);
    private static final Set<String> VALID_TASK_STATUSES = Set.of(
            WecomCustomerTagPushTask.STATUS_PENDING,
            WecomCustomerTagPushTask.STATUS_PROCESSING,
            WecomCustomerTagPushTask.STATUS_SUCCEEDED,
            WecomCustomerTagPushTask.STATUS_RETRYABLE,
            WecomCustomerTagPushTask.STATUS_FAILED);
    private static final Logger log = LoggerFactory.getLogger(WecomTagSyncService.class);

    private final WecomTagSyncRepository repository;
    private final WecomTagClientRouter clientProvider;
    private final WecomOfficialProperties officialProperties;
    private final DictionaryApplicationService dictionaryService;
    private final CustomerApplicationService customerService;
    private final CustomerScopeSupport customerScopeService;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final long runLeaseSeconds;
    private final long taskLeaseSeconds;

    @Autowired
    public WecomTagSyncService(WecomTagSyncRepository repository,
                               WecomTagClientRouter clientProvider,
                               WecomOfficialProperties officialProperties,
                               DictionaryApplicationService dictionaryService,
                               CustomerApplicationService customerService,
                               CustomerScopeSupport customerScopeService,
                               AuditService auditService,
                               IdempotencyService idempotencyService,
                               ObjectMapper objectMapper,
                               @Value("${app.wecom.official.sync-lease-seconds:300}") long runLeaseSeconds,
                               @Value("${app.wecom.tag-sync.task-lease-seconds:60}") long taskLeaseSeconds) {
        this.repository = repository;
        this.clientProvider = clientProvider;
        this.officialProperties = officialProperties;
        this.dictionaryService = dictionaryService;
        this.customerService = customerService;
        this.customerScopeService = customerScopeService;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.runLeaseSeconds = runLeaseSeconds > 0 ? runLeaseSeconds : DEFAULT_RUN_LEASE_SECONDS;
        this.taskLeaseSeconds = taskLeaseSeconds > 0 ? taskLeaseSeconds : DEFAULT_TASK_LEASE_SECONDS;
    }

    public WecomTagSyncService(WecomTagSyncRepository repository,
                               WecomTagClientRouter clientProvider,
                               WecomOfficialProperties officialProperties,
                               DictionaryApplicationService dictionaryService,
                               CustomerApplicationService customerService,
                               CustomerScopeSupport customerScopeService,
                               AuditService auditService,
                               IdempotencyService idempotencyService,
                               ObjectMapper objectMapper) {
        this(repository, clientProvider, officialProperties, dictionaryService, customerService,
                customerScopeService, auditService, idempotencyService, objectMapper,
                DEFAULT_RUN_LEASE_SECONDS, DEFAULT_TASK_LEASE_SECONDS);
    }

    public WecomTagMappingOverviewResponse getMappingsOverview(long tenantId) {
        boolean officialEnabled = clientProvider.isOfficialEnabled(tenantId);
        WecomTagClient client = clientProvider.client(tenantId);
        String corpId = resolveCorpId(client);
        String adapterName = client != null ? client.name() : "DISABLED";
        List<DictionaryItemResponse> crmTags = listCustomerTagItems(tenantId);
        Map<String, WecomTagMapping> mappingByCode = new LinkedHashMap<>();
        if (!corpId.isBlank()) {
            for (WecomTagMapping mapping : repository.findMappings(tenantId, corpId)) {
                mappingByCode.put(mapping.crmTagCode(), mapping);
            }
        }
        List<WecomTagMappingItemResponse> items = crmTags.stream()
                .map(crmTag -> toMappingItemResponse(crmTag, mappingByCode.get(crmTag.optionCode())))
                .toList();
        return new WecomTagMappingOverviewResponse(corpId, adapterName, officialEnabled, items);
    }

    public WecomTagMappingItemResponse updateMapping(long tenantId, long actorUserId, String crmTagCode,
                                                     UpdateWecomTagMappingRequest request) {
        String normalizedCode = crmTagCode == null ? "" : crmTagCode.strip();
        DictionaryItemResponse crmTag = listCustomerTagItems(tenantId).stream()
                .filter(item -> item.optionCode().equals(normalizedCode))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "CRM 客户标签不存在"));
        WecomTagClient client = requireClient(tenantId);
        String corpId = requireCorpId(client);
        String action = request.action() == null ? "" : request.action().strip().toUpperCase(Locale.ROOT);
        if (!"BIND".equals(action) && !"UNLINK".equals(action)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "映射操作仅支持 BIND 或 UNLINK");
        }
        Optional<WecomTagMapping> before = repository.findMappingByCrmTagCode(tenantId, corpId, normalizedCode);
        Instant now = Instant.now();
        if ("BIND".equals(action)) {
            String wecomTagId = blankToNull(request.wecomTagId());
            String wecomTagName = blankToNull(request.wecomTagName());
            if (wecomTagId == null || wecomTagName == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "绑定映射时企微标签 ID 与名称不能为空");
            }
            String groupId = blankToNull(request.wecomGroupId());
            String groupName = blankToNull(request.wecomGroupName());
            try {
                repository.upsertMapping(tenantId, corpId, normalizedCode, groupId, groupName, wecomTagId,
                        wecomTagName, WecomTagMapping.STATUS_ACTIVE, now, actorUserId);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(ErrorCode.CONFLICT, "该企微标签已绑定到其他 CRM 标签");
            }
        } else {
            repository.upsertMapping(tenantId, corpId, normalizedCode, null, null, null, null,
                    WecomTagMapping.STATUS_UNLINKED, now, actorUserId);
        }
        WecomTagMapping after = repository.findMappingByCrmTagCode(tenantId, corpId, normalizedCode).orElseThrow();
        auditService.record(tenantId, actorUserId, AuditService.ACTION_WECOM_TAG_MAPPING_UPDATE,
                "WECOM_TAG_MAPPING", normalizedCode,
                before.map(this::mappingSnapshot).orElse(null),
                mappingSnapshot(after), action);
        return toMappingItemResponse(crmTag, after);
    }

    public WecomTagSyncRunResponse syncCatalog(long tenantId, Long actorUserId, String requestedDirection,
                                               String triggerSource, String idempotencyKey) {
        String direction = normalizeDirection(requestedDirection);
        String payload = WecomTagSyncRun.SCOPE_CATALOG + "|" + direction;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, IDEMPOTENCY_SCOPE_CATALOG,
                    actorUserId, idempotencyKey, payload);
            if (replay.isPresent()) {
                return loadRunResponse(tenantId, Long.parseLong(replay.get().getResourceId()));
            }
        }
        WecomTagClient client = requireClient(tenantId);
        String corpId = requireCorpId(client);
        Instant now = Instant.now();
        long runId = repository.startRun(tenantId, corpId, WecomTagSyncRun.SCOPE_CATALOG, direction,
                triggerSource, client.name(), actorUserId, now, now.plusSeconds(runLeaseSeconds));
        if (runId == 0L) {
            throw new BusinessException(ErrorCode.CONFLICT, "该企业已有标签库同步正在运行，请稍后重试");
        }
        SyncCounters counters = new SyncCounters();
        try {
            if (WecomTagSyncRun.DIRECTION_PULL.equals(direction)
                    || WecomTagSyncRun.DIRECTION_BIDIRECTIONAL.equals(direction)) {
                doPullCatalog(tenantId, corpId, client, actorUserId, counters);
            }
            if (WecomTagSyncRun.DIRECTION_PUSH.equals(direction)
                    || WecomTagSyncRun.DIRECTION_BIDIRECTIONAL.equals(direction)) {
                doPushCatalog(tenantId, corpId, client, actorUserId, counters);
            }
            String finalStatus = counters.finalStatus();
            repository.finishRun(tenantId, runId, finalStatus, counters.pulled, counters.pushed, counters.skipped,
                    counters.failed, counters.firstError, Instant.now());
            if (idempotencyKey != null && !idempotencyKey.isBlank()
                    && !WecomTagSyncRun.STATUS_ERROR.equals(finalStatus)) {
                idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_CATALOG, actorUserId, idempotencyKey,
                        "WECOM_TAG_SYNC_RUN", Long.toString(runId), payload);
            }
            auditService.record(tenantId, actorUserId, AuditService.ACTION_WECOM_TAG_CATALOG_SYNC,
                    "WECOM_TAG_SYNC_RUN", Long.toString(runId), null,
                    runAuditSnapshot(corpId, WecomTagSyncRun.SCOPE_CATALOG, direction, triggerSource,
                            client.name(), finalStatus, counters), null);
            return loadRunResponse(tenantId, runId);
        } catch (RuntimeException ex) {
            String error = maskError(ex);
            repository.finishRun(tenantId, runId, WecomTagSyncRun.STATUS_ERROR, counters.pulled, counters.pushed,
                    counters.skipped, Math.max(1, counters.failed), error, Instant.now());
            if (ex instanceof BusinessException business) {
                throw business;
            }
            throw new BusinessException(ErrorCode.CONFLICT, "企微标签库同步失败，请稍后重试");
        }
    }

    public WecomTagSyncRunResponse syncCustomers(long tenantId, CurrentUser user, String requestedDirection,
                                                 String triggerSource, String idempotencyKey) {
        Long actorUserId = user == null ? null : user.userId();
        String direction = normalizeDirection(requestedDirection);
        String payload = WecomTagSyncRun.SCOPE_CUSTOMER + "|" + direction;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, IDEMPOTENCY_SCOPE_CUSTOMERS,
                    actorUserId, idempotencyKey, payload);
            if (replay.isPresent()) {
                return loadRunResponse(tenantId, Long.parseLong(replay.get().getResourceId()));
            }
        }
        WecomTagClient client = requireClient(tenantId);
        String corpId = requireCorpId(client);
        Instant now = Instant.now();
        long runId = repository.startRun(tenantId, corpId, WecomTagSyncRun.SCOPE_CUSTOMER, direction,
                triggerSource, client.name(), actorUserId, now, now.plusSeconds(runLeaseSeconds));
        if (runId == 0L) {
            throw new BusinessException(ErrorCode.CONFLICT, "该企业已有客户标签同步正在运行，请稍后重试");
        }
        SyncCounters counters = new SyncCounters();
        try {
            List<WecomTagMapping> activeMappings = activeMappings(tenantId, corpId);
            List<WecomLinkedContactForTagSync> linkedContacts = filterVisibleContacts(
                    tenantId, user, repository.findAllLinkedContacts(tenantId, corpId));
            if (activeMappings.isEmpty() || linkedContacts.isEmpty()) {
                counters.skipped += linkedContacts.size();
            } else {
                if (WecomTagSyncRun.DIRECTION_PULL.equals(direction)
                        || WecomTagSyncRun.DIRECTION_BIDIRECTIONAL.equals(direction)) {
                    doPullAllCustomerTags(tenantId, activeMappings, linkedContacts, actorUserId, counters);
                }
                if (WecomTagSyncRun.DIRECTION_PUSH.equals(direction)
                        || WecomTagSyncRun.DIRECTION_BIDIRECTIONAL.equals(direction)) {
                    doPushAllCustomerTags(tenantId, corpId, client, activeMappings, linkedContacts,
                            actorUserId, counters);
                }
            }
            String finalStatus = counters.finalStatus();
            repository.finishRun(tenantId, runId, finalStatus, counters.pulled, counters.pushed, counters.skipped,
                    counters.failed, counters.firstError, Instant.now());
            if (idempotencyKey != null && !idempotencyKey.isBlank()
                    && !WecomTagSyncRun.STATUS_ERROR.equals(finalStatus)) {
                idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_CUSTOMERS, actorUserId, idempotencyKey,
                        "WECOM_TAG_SYNC_RUN", Long.toString(runId), payload);
            }
            return loadRunResponse(tenantId, runId);
        } catch (RuntimeException ex) {
            String error = maskError(ex);
            repository.finishRun(tenantId, runId, WecomTagSyncRun.STATUS_ERROR, counters.pulled, counters.pushed,
                    counters.skipped, Math.max(1, counters.failed), error, Instant.now());
            if (ex instanceof BusinessException business) {
                throw business;
            }
            throw new BusinessException(ErrorCode.CONFLICT, "企微客户标签同步失败，请稍后重试");
        }
    }

    public WecomCustomerTagPushResponse pushCustomerTags(long tenantId, CurrentUser user, long customerId,
                                                         String idempotencyKey) {
        customerScopeService.requireVisible(tenantId, customerId, user);
        boolean officialEnabled = clientProvider.isOfficialEnabled(tenantId);
        WecomTagClient client = requireClient(tenantId);
        String corpId = requireCorpId(client);
        String payload = "PUSH_CUSTOMER|" + customerId;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, IDEMPOTENCY_SCOPE_CUSTOMER_PUSH,
                    user.userId(), idempotencyKey, payload);
            if (replay.isPresent()) {
                return decodePushReplay(customerId, client.name(), officialEnabled, replay.get().getResourceId());
            }
        }
        List<WecomTagMapping> activeMappings = activeMappings(tenantId, corpId);
        List<WecomLinkedContactForTagSync> contacts = repository.findLinkedContactsByCustomerId(
                tenantId, corpId, customerId);
        if (contacts.isEmpty()) {
            WecomCustomerTagPushResponse empty = new WecomCustomerTagPushResponse(
                    customerId, client.name(), officialEnabled, 0, 0, 0, 0, null);
            recordPushIdempotency(tenantId, user.userId(), idempotencyKey, payload, empty);
            return empty;
        }
        List<String> crmTags = customerService.getCustomerTagCodesInternal(tenantId, customerId);
        Map<String, String> crmToWecom = crmToWecomMap(activeMappings);
        Set<String> managedWecomIds = new LinkedHashSet<>(crmToWecom.values());
        Set<String> desiredWecomIds = new LinkedHashSet<>();
        for (String crmCode : crmTags) {
            String wecomTagId = crmToWecom.get(crmCode);
            if (wecomTagId != null) {
                desiredWecomIds.add(wecomTagId);
            }
        }
        int pushed = 0;
        int skipped = 0;
        int failed = 0;
        String firstError = null;
        for (WecomLinkedContactForTagSync contact : contacts) {
            Set<String> currentWecomIds = parseWecomTagIds(contact.tagSnapshotJson());
            List<String> addIds = new ArrayList<>();
            for (String desired : desiredWecomIds) {
                if (!currentWecomIds.contains(desired)) {
                    addIds.add(desired);
                }
            }
            List<String> removeIds = new ArrayList<>();
            for (String current : currentWecomIds) {
                if (managedWecomIds.contains(current) && !desiredWecomIds.contains(current)) {
                    removeIds.add(current);
                }
            }
            if (addIds.isEmpty() && removeIds.isEmpty()) {
                skipped++;
                continue;
            }
            TaskExecutionResult outcome = enqueueAndExecuteSyncPush(tenantId, corpId, client, contact,
                    addIds, removeIds, "MANUAL", user.userId());
            if (outcome.succeeded) {
                pushed++;
            } else {
                failed++;
                if (firstError == null) {
                    firstError = outcome.errorMasked;
                }
            }
        }
        WecomCustomerTagPushResponse response = new WecomCustomerTagPushResponse(
                customerId, client.name(), officialEnabled, contacts.size(), pushed, skipped, failed, firstError);
        if (failed == 0) {
            recordPushIdempotency(tenantId, user.userId(), idempotencyKey, payload, response);
        }
        return response;
    }

    public List<WecomTagSyncRunResponse> listRuns(long tenantId) {
        WecomTagClient client = clientProvider.client(tenantId);
        String corpId = resolveCorpId(client);
        if (corpId.isBlank()) {
            return List.of();
        }
        return repository.findRecentRuns(tenantId, corpId, DEFAULT_RECENT_RUN_LIMIT).stream()
                .map(this::toRunResponse)
                .toList();
    }

    public List<WecomTagPushTaskResponse> listTasks(long tenantId, CurrentUser user, String status) {
        String normalizedStatus = null;
        if (status != null && !status.isBlank()) {
            normalizedStatus = status.strip().toUpperCase(Locale.ROOT);
            if (!VALID_TASK_STATUSES.contains(normalizedStatus)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "无效的任务状态过滤条件");
            }
        }
        DataScope scope = customerScopeService.scope(user);
        if (customerScopeService.isEmptyScope(scope)) {
            return List.of();
        }
        WecomTagClient client = clientProvider.client(tenantId);
        String corpId = resolveCorpId(client);
        if (corpId.isBlank()) {
            return List.of();
        }
        if (scope.type() == DataScope.ScopeType.ALL) {
            return repository.findPushTasks(tenantId, corpId, normalizedStatus, DEFAULT_TASK_LIST_LIMIT).stream()
                    .map(this::toTaskResponse)
                    .toList();
        }
        List<WecomCustomerTagPushTask> candidates = repository.findPushTasks(
                tenantId, corpId, normalizedStatus, SCOPED_TASK_FETCH_LIMIT);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<Long> customerIds = new LinkedHashSet<>();
        for (WecomCustomerTagPushTask task : candidates) {
            customerIds.add(task.customerId());
        }
        Set<Long> visibleCustomerIds = customerService.filterVisibleCustomerIds(tenantId, user, customerIds);
        if (visibleCustomerIds.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(task -> visibleCustomerIds.contains(task.customerId()))
                .limit(DEFAULT_TASK_LIST_LIMIT)
                .map(this::toTaskResponse)
                .toList();
    }

    public WecomTagPushTaskResponse retryTask(long tenantId, CurrentUser user, long taskId) {
        WecomCustomerTagPushTask existing = repository.findPushTaskById(tenantId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "标签推送任务不存在"));
        customerScopeService.requireVisible(tenantId, existing.customerId(), user);
        if (!WecomCustomerTagPushTask.STATUS_FAILED.equals(existing.status())
                && !WecomCustomerTagPushTask.STATUS_RETRYABLE.equals(existing.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅失败或待重试状态的推送任务支持手动重试");
        }
        WecomTagClient client = requireClient(tenantId);
        Instant now = Instant.now();
        int reset = repository.resetPushTaskForRetry(tenantId, taskId, now);
        if (reset != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "推送任务状态已变化，请刷新后重试");
        }
        WecomCustomerTagPushTask ready = repository.findPushTaskById(tenantId, taskId).orElseThrow();
        executeClaimedOrPendingTask(ready, client, user.userId());
        return toTaskResponse(repository.findPushTaskById(tenantId, taskId).orElseThrow());
    }

    /**
     * CustomerTagSyncPort：本地 CRM 标签写事务内调用，仅入队异步推送任务，绝不抛出异常回滚本地写。
     */
    @Override
    public void onCustomerTagsChanged(long tenantId, long customerId, List<String> beforeTagCodes,
                                      List<String> afterTagCodes, Long actorUserId, String triggerSource) {
        try {
            WecomTagClient client = clientProvider.client(tenantId);
            if (client == null || client.corpId() == null || client.corpId().isBlank()) {
                return;
            }
            String corpId = client.corpId().strip();
            Map<String, String> crmToWecom = crmToWecomMap(activeMappings(tenantId, corpId));
            if (crmToWecom.isEmpty()) {
                return;
            }
            Set<String> beforeSet = beforeTagCodes == null ? Set.of() : new LinkedHashSet<>(beforeTagCodes);
            Set<String> afterSet = afterTagCodes == null ? Set.of() : new LinkedHashSet<>(afterTagCodes);
            Set<String> addWecomIds = new LinkedHashSet<>();
            for (String code : afterSet) {
                if (!beforeSet.contains(code)) {
                    String mapped = crmToWecom.get(code);
                    if (mapped != null) {
                        addWecomIds.add(mapped);
                    }
                }
            }
            Set<String> removeWecomIds = new LinkedHashSet<>();
            for (String code : beforeSet) {
                if (!afterSet.contains(code)) {
                    String mapped = crmToWecom.get(code);
                    if (mapped != null) {
                        removeWecomIds.add(mapped);
                    }
                }
            }
            if (addWecomIds.isEmpty() && removeWecomIds.isEmpty()) {
                return;
            }
            List<WecomLinkedContactForTagSync> contacts = repository.findLinkedContactsByCustomerId(
                    tenantId, corpId, customerId);
            if (contacts.isEmpty()) {
                return;
            }
            String addJson = toJsonArray(addWecomIds);
            String removeJson = toJsonArray(removeWecomIds);
            String source = triggerSource == null || triggerSource.isBlank() ? "CRM_WRITE" : triggerSource;
            for (WecomLinkedContactForTagSync contact : contacts) {
                repository.insertPushTask(tenantId, corpId, customerId, contact.wecomContactId(),
                        contact.wecomUserid(), contact.externalUserid(), addJson, removeJson,
                        DEFAULT_MAX_ATTEMPTS, null, source, actorUserId);
            }
        } catch (RuntimeException ex) {
            log.warn("企微客户标签变更入队异常（已忽略以保证本地CRM事务不受影响）: customerId={}", customerId);
        }
    }

    /**
     * 后台定时任务处理器：租约回收 + 认领待推送任务 + 指数退避重试。
     */
    public int processPendingPushTasks() {
        Instant now = Instant.now();
        repository.recoverExpiredPushTasks(now, "企微标签推送租约已过期，已自动回收");
        List<WecomCustomerTagPushTask> pending = repository.findPendingPushTasks(now, DEFAULT_WORKER_BATCH_SIZE);
        int processed = 0;
        for (WecomCustomerTagPushTask task : pending) {
            WecomTagClient client = clientProvider.client(task.tenantId());
            if (client == null) {
                continue;
            }
            if (executeClaimedOrPendingTask(task, client, task.createdBy())) {
                processed++;
            }
        }
        return processed;
    }

    /**
     * 企微回调 change_external_tag 触发的标签库拉取。
     */
    public void handleTagCatalogCallback(long tenantId, String changeType) {
        if (clientProvider.client(tenantId) == null) {
            return;
        }
        try {
            syncCatalog(tenantId, null, WecomTagSyncRun.DIRECTION_PULL, WecomTagSyncRun.TRIGGER_CALLBACK, null);
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.CONFLICT) {
                log.info("企微标签回调到达时已有标签库同步在运行，跳过本次重复拉取: tenantId={}, changeType={}",
                        tenantId, changeType);
                return;
            }
            throw ex;
        }
    }

    /**
     * 企微联系人同步 / 回调刷新后触发的 WeCom → CRM 客户标签回流（仅作用于人工关联客户，不触发反向推送）。
     */
    public void pullCustomerTagsForExternalUser(long tenantId, String externalUserid, String triggerSource) {
        if (externalUserid == null || externalUserid.isBlank()) {
            return;
        }
        WecomTagClient client = clientProvider.client(tenantId);
        if (client == null || client.corpId() == null || client.corpId().isBlank()) {
            return;
        }
        String corpId = client.corpId().strip();
        List<WecomTagMapping> activeMappings = activeMappings(tenantId, corpId);
        if (activeMappings.isEmpty()) {
            return;
        }
        List<WecomLinkedContactForTagSync> linked = repository.findLinkedContactsByExternalUserid(
                tenantId, corpId, externalUserid.strip());
        if (linked.isEmpty()) {
            return;
        }
        SyncCounters ignored = new SyncCounters();
        doPullAllCustomerTags(tenantId, activeMappings, linked, null, ignored);
    }

    /**
     * 解析 crm_wecom_contact.tag_snapshot_json，同时兼容对象数组 [{"tag_id":"..."}] 与字符串数组 ["tag_id"]。
     */
    public Set<String> parseWecomTagIds(String tagSnapshotJson) {
        Set<String> ids = new LinkedHashSet<>();
        if (tagSnapshotJson == null || tagSnapshotJson.isBlank()) {
            return ids;
        }
        try {
            JsonNode root = objectMapper.readTree(tagSnapshotJson);
            if (!root.isArray()) {
                return ids;
            }
            for (JsonNode element : root) {
                if (element.isTextual()) {
                    String val = element.asText("").strip();
                    if (!val.isEmpty()) {
                        ids.add(val);
                    }
                } else if (element.isObject()) {
                    String tagId = element.path("tag_id").asText("").strip();
                    if (tagId.isEmpty()) {
                        tagId = element.path("id").asText("").strip();
                    }
                    if (!tagId.isEmpty()) {
                        ids.add(tagId);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("企微标签快照 JSON 解析失败，按空标签处理");
        }
        return ids;
    }

    static String maskExternalUserid(String externalUserid) {
        if (externalUserid == null || externalUserid.isBlank()) {
            return "";
        }
        String value = externalUserid.strip();
        if (value.length() <= 4) {
            return "****";
        }
        if (value.length() <= 8) {
            return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
        }
        return value.substring(0, 3) + "***" + value.substring(value.length() - 3);
    }

    // ========== 内部同步实现 ==========

    private void doPullCatalog(long tenantId, String corpId, WecomTagClient client, Long actorUserId,
                               SyncCounters counters) {
        List<WecomCorpTag> remoteTags = client.listCorpTags();
        List<DictionaryItemResponse> crmItems = new ArrayList<>(listCustomerTagItems(tenantId));
        List<WecomTagMapping> existingMappings = repository.findMappings(tenantId, corpId);

        Map<String, WecomTagMapping> mappingByWecomId = new LinkedHashMap<>();
        Map<String, WecomTagMapping> mappingByCrmCode = new LinkedHashMap<>();
        for (WecomTagMapping mapping : existingMappings) {
            mappingByCrmCode.put(mapping.crmTagCode(), mapping);
            if (mapping.wecomTagId() != null && !mapping.wecomTagId().isBlank()) {
                mappingByWecomId.put(mapping.wecomTagId(), mapping);
            }
        }
        Map<String, DictionaryItemResponse> crmByCode = new LinkedHashMap<>();
        Map<String, DictionaryItemResponse> crmByLabel = new LinkedHashMap<>();
        for (DictionaryItemResponse item : crmItems) {
            crmByCode.put(item.optionCode(), item);
            crmByLabel.put(normalizeLabelKey(item.optionLabel()), item);
        }

        Set<String> seenRemoteIds = new LinkedHashSet<>();
        Instant now = Instant.now();
        long actor = actorUserId == null ? 0L : actorUserId;

        for (WecomCorpTag remote : remoteTags) {
            String remoteId = blankToNull(remote.tagId());
            String remoteName = blankToNull(remote.tagName());
            if (remoteId == null || remoteName == null) {
                counters.skipped++;
                continue;
            }
            seenRemoteIds.add(remoteId);
            WecomTagMapping existing = mappingByWecomId.get(remoteId);
            if (existing != null) {
                DictionaryItemResponse crmItem = crmByCode.get(existing.crmTagCode());
                if (crmItem == null) {
                    counters.skipped++;
                    continue;
                }
                if (!"ACTIVE".equals(crmItem.status())) {
                    counters.skipped++;
                    continue;
                }
                boolean changed = false;
                if (!crmItem.optionLabel().strip().equals(remoteName)) {
                    try {
                        DictionaryItemResponse updated = dictionaryService.updateItemForType(
                                tenantId, actor, CustomerTagAdminService.CUSTOMER_TAGS_DICT_CODE, crmItem.id(),
                                new UpdateDictionaryItemRequest(remoteName, crmItem.sortNo(), crmItem.version()), true);
                        crmByCode.put(updated.optionCode(), updated);
                        crmByLabel.put(normalizeLabelKey(updated.optionLabel()), updated);
                        changed = true;
                    } catch (BusinessException ex) {
                        counters.recordFailure(maskError(ex));
                        continue;
                    }
                }
                if (!WecomTagMapping.STATUS_ACTIVE.equals(existing.syncStatus())
                        || !remoteName.equals(existing.wecomTagName())
                        || !java.util.Objects.equals(remote.groupId(), existing.wecomGroupId())
                        || !java.util.Objects.equals(remote.groupName(), existing.wecomGroupName())) {
                    changed = true;
                }
                repository.upsertMapping(tenantId, corpId, crmItem.optionCode(), remote.groupId(),
                        remote.groupName(), remoteId, remoteName, WecomTagMapping.STATUS_ACTIVE, now, actorUserId);
                if (changed) {
                    counters.pulled++;
                } else {
                    counters.skipped++;
                }
                continue;
            }

            DictionaryItemResponse matchedByLabel = crmByLabel.get(normalizeLabelKey(remoteName));
            if (matchedByLabel != null) {
                WecomTagMapping existingForCode = mappingByCrmCode.get(matchedByLabel.optionCode());
                if (!"ACTIVE".equals(matchedByLabel.status())) {
                    counters.skipped++;
                    continue;
                }
                if (existingForCode != null && WecomTagMapping.STATUS_ACTIVE.equals(existingForCode.syncStatus())
                        && existingForCode.wecomTagId() != null) {
                    counters.skipped++;
                    continue;
                }
                repository.upsertMapping(tenantId, corpId, matchedByLabel.optionCode(), remote.groupId(),
                        remote.groupName(), remoteId, remoteName, WecomTagMapping.STATUS_ACTIVE, now, actorUserId);
                WecomTagMapping bound = repository.findMappingByCrmTagCode(tenantId, corpId,
                        matchedByLabel.optionCode()).orElse(null);
                if (bound != null) {
                    mappingByCrmCode.put(bound.crmTagCode(), bound);
                    mappingByWecomId.put(remoteId, bound);
                }
                counters.pulled++;
                continue;
            }

            try {
                DictionaryItemResponse created = dictionaryService.createItemForType(
                        tenantId, actor, CustomerTagAdminService.CUSTOMER_TAGS_DICT_CODE,
                        generateTagCode(), remoteName, Math.max(0, remote.order()), true);
                crmByCode.put(created.optionCode(), created);
                crmByLabel.put(normalizeLabelKey(created.optionLabel()), created);
                repository.upsertMapping(tenantId, corpId, created.optionCode(), remote.groupId(),
                        remote.groupName(), remoteId, remoteName, WecomTagMapping.STATUS_ACTIVE, now, actorUserId);
                WecomTagMapping bound = repository.findMappingByCrmTagCode(tenantId, corpId,
                        created.optionCode()).orElse(null);
                if (bound != null) {
                    mappingByCrmCode.put(bound.crmTagCode(), bound);
                    mappingByWecomId.put(remoteId, bound);
                }
                counters.pulled++;
            } catch (BusinessException ex) {
                counters.recordFailure(maskError(ex));
            }
        }

        // 企微端已删除的标签：仅将映射置为 UNLINKED，严禁删除 CRM 历史标签！
        for (WecomTagMapping existing : existingMappings) {
            if (WecomTagMapping.STATUS_ACTIVE.equals(existing.syncStatus())
                    && existing.wecomTagId() != null
                    && !seenRemoteIds.contains(existing.wecomTagId())) {
                repository.upsertMapping(tenantId, corpId, existing.crmTagCode(), existing.wecomGroupId(),
                        existing.wecomGroupName(), null, existing.wecomTagName(),
                        WecomTagMapping.STATUS_UNLINKED, now, actorUserId);
                counters.pulled++;
            }
        }
    }

    private void doPushCatalog(long tenantId, String corpId, WecomTagClient client, Long actorUserId,
                               SyncCounters counters) {
        List<DictionaryItemResponse> crmItems = listCustomerTagItems(tenantId);
        List<WecomTagMapping> mappings = repository.findMappings(tenantId, corpId);
        List<WecomCorpTag> remoteTags = client.listCorpTags();

        Map<String, WecomTagMapping> mappingByCode = new LinkedHashMap<>();
        Set<String> mappedWecomIds = new LinkedHashSet<>();
        for (WecomTagMapping mapping : mappings) {
            mappingByCode.put(mapping.crmTagCode(), mapping);
            if (WecomTagMapping.STATUS_ACTIVE.equals(mapping.syncStatus()) && mapping.wecomTagId() != null) {
                mappedWecomIds.add(mapping.wecomTagId());
            }
        }
        String defaultGroupName = officialProperties.defaultTagGroupName();
        String defaultGroupId = null;
        Map<String, WecomCorpTag> remoteByLabel = new LinkedHashMap<>();
        for (WecomCorpTag remote : remoteTags) {
            if (defaultGroupId == null && defaultGroupName.equals(remote.groupName())
                    && remote.groupId() != null && !remote.groupId().isBlank()) {
                defaultGroupId = remote.groupId();
            }
            if (remote.tagName() != null) {
                remoteByLabel.putIfAbsent(normalizeLabelKey(remote.tagName()), remote);
            }
        }
        Instant now = Instant.now();

        for (DictionaryItemResponse crmItem : crmItems) {
            WecomTagMapping existing = mappingByCode.get(crmItem.optionCode());
            if ("ACTIVE".equals(crmItem.status())) {
                if (existing != null && WecomTagMapping.STATUS_UNLINKED.equals(existing.syncStatus())) {
                    counters.skipped++;
                    continue;
                }
                if (existing != null && WecomTagMapping.STATUS_ACTIVE.equals(existing.syncStatus())
                        && existing.wecomTagId() != null && !existing.wecomTagId().isBlank()) {
                    String desiredLabel = crmItem.optionLabel().strip();
                    if (!desiredLabel.equals(existing.wecomTagName())) {
                        try {
                            client.editCorpTag(existing.wecomTagId(), desiredLabel, safeOrder(crmItem.sortNo()));
                            repository.upsertMapping(tenantId, corpId, crmItem.optionCode(), existing.wecomGroupId(),
                                    existing.wecomGroupName(), existing.wecomTagId(), desiredLabel,
                                    WecomTagMapping.STATUS_ACTIVE, now, actorUserId);
                            counters.pushed++;
                        } catch (RuntimeException ex) {
                            counters.recordFailure(maskError(ex));
                        }
                    } else {
                        counters.skipped++;
                    }
                    continue;
                }

                // 未映射或曾 DISABLED 但现已重新启用的 CRM 活跃标签
                String labelKey = normalizeLabelKey(crmItem.optionLabel());
                WecomCorpTag remoteMatch = remoteByLabel.get(labelKey);
                if (remoteMatch != null && !mappedWecomIds.contains(remoteMatch.tagId())) {
                    repository.upsertMapping(tenantId, corpId, crmItem.optionCode(), remoteMatch.groupId(),
                            remoteMatch.groupName(), remoteMatch.tagId(), remoteMatch.tagName(),
                            WecomTagMapping.STATUS_ACTIVE, now, actorUserId);
                    mappedWecomIds.add(remoteMatch.tagId());
                    counters.pushed++;
                    continue;
                }
                try {
                    WecomCorpTag created = client.addCorpTag(defaultGroupId, defaultGroupName,
                            crmItem.optionLabel().strip(), safeOrder(crmItem.sortNo()));
                    if (defaultGroupId == null && created.groupId() != null && !created.groupId().isBlank()) {
                        defaultGroupId = created.groupId();
                    }
                    repository.upsertMapping(tenantId, corpId, crmItem.optionCode(), created.groupId(),
                            created.groupName(), created.tagId(), created.tagName(),
                            WecomTagMapping.STATUS_ACTIVE, now, actorUserId);
                    mappedWecomIds.add(created.tagId());
                    counters.pushed++;
                } catch (RuntimeException ex) {
                    counters.recordFailure(maskError(ex));
                }
            } else {
                // 已停用/已归档 CRM 标签：仅处理 ACTIVE 映射（删除企微标签并置 DISABLED），不影响 CRM 历史标签
                if (existing != null && WecomTagMapping.STATUS_ACTIVE.equals(existing.syncStatus())
                        && existing.wecomTagId() != null && !existing.wecomTagId().isBlank()) {
                    try {
                        client.deleteCorpTag(existing.wecomTagId());
                        repository.upsertMapping(tenantId, corpId, crmItem.optionCode(), existing.wecomGroupId(),
                                existing.wecomGroupName(), null, existing.wecomTagName(),
                                WecomTagMapping.STATUS_DISABLED, now, actorUserId);
                        counters.pushed++;
                    } catch (RuntimeException ex) {
                        counters.recordFailure(maskError(ex));
                    }
                } else {
                    counters.skipped++;
                }
            }
        }
    }

    private void doPullAllCustomerTags(long tenantId, List<WecomTagMapping> activeMappings,
                                       List<WecomLinkedContactForTagSync> linkedContacts, Long actorUserId,
                                       SyncCounters counters) {
        Set<String> activeCrmTagCodes = new LinkedHashSet<>();
        dictionaryService.listActiveOptions(tenantId, CustomerTagAdminService.CUSTOMER_TAGS_DICT_CODE)
                .forEach(opt -> activeCrmTagCodes.add(opt.optionCode()));
        Map<String, String> wecomToCrm = new LinkedHashMap<>();
        Set<String> managedCrmCodes = new LinkedHashSet<>();
        for (WecomTagMapping mapping : activeMappings) {
            if (mapping.wecomTagId() != null && !mapping.wecomTagId().isBlank()
                    && activeCrmTagCodes.contains(mapping.crmTagCode())) {
                wecomToCrm.put(mapping.wecomTagId(), mapping.crmTagCode());
                managedCrmCodes.add(mapping.crmTagCode());
            }
        }
        if (managedCrmCodes.isEmpty()) {
            counters.skipped += linkedContacts.size();
            return;
        }
        Map<Long, List<WecomLinkedContactForTagSync>> byCustomer = new LinkedHashMap<>();
        for (WecomLinkedContactForTagSync contact : linkedContacts) {
            byCustomer.computeIfAbsent(contact.customerId(), ignored -> new ArrayList<>()).add(contact);
        }
        for (Map.Entry<Long, List<WecomLinkedContactForTagSync>> entry : byCustomer.entrySet()) {
            long customerId = entry.getKey();
            boolean hasSnapshot = false;
            Set<String> targetCrmCodes = new LinkedHashSet<>();
            for (WecomLinkedContactForTagSync contact : entry.getValue()) {
                if (contact.tagSnapshotJson() != null && !contact.tagSnapshotJson().isBlank()) {
                    hasSnapshot = true;
                    for (String wecomTagId : parseWecomTagIds(contact.tagSnapshotJson())) {
                        String crmCode = wecomToCrm.get(wecomTagId);
                        if (crmCode != null) {
                            targetCrmCodes.add(crmCode);
                        }
                    }
                }
            }
            if (!hasSnapshot) {
                counters.skipped++;
                continue;
            }
            try {
                CustomerApplicationService.WecomTagPullOutcome outcome = customerService.syncTagsFromWecom(
                        tenantId, customerId, managedCrmCodes, targetCrmCodes, actorUserId, "企微客户标签回流同步");
                if (outcome.changed()) {
                    counters.pulled++;
                } else {
                    counters.skipped++;
                }
            } catch (RuntimeException ex) {
                counters.recordFailure(maskError(ex));
            }
        }
    }

    private void doPushAllCustomerTags(long tenantId, String corpId, WecomTagClient client,
                                       List<WecomTagMapping> activeMappings,
                                       List<WecomLinkedContactForTagSync> linkedContacts,
                                       Long actorUserId, SyncCounters counters) {
        Map<String, String> crmToWecom = crmToWecomMap(activeMappings);
        Set<String> managedWecomIds = new LinkedHashSet<>(crmToWecom.values());
        Map<Long, Set<String>> desiredByCustomer = new LinkedHashMap<>();

        for (WecomLinkedContactForTagSync contact : linkedContacts) {
            Set<String> desiredWecomIds = desiredByCustomer.computeIfAbsent(contact.customerId(), cid -> {
                Set<String> ids = new LinkedHashSet<>();
                for (String crmCode : customerService.getCustomerTagCodesInternal(tenantId, cid)) {
                    String mapped = crmToWecom.get(crmCode);
                    if (mapped != null) {
                        ids.add(mapped);
                    }
                }
                return ids;
            });
            Set<String> currentWecomIds = parseWecomTagIds(contact.tagSnapshotJson());
            List<String> addIds = new ArrayList<>();
            for (String desired : desiredWecomIds) {
                if (!currentWecomIds.contains(desired)) {
                    addIds.add(desired);
                }
            }
            List<String> removeIds = new ArrayList<>();
            for (String current : currentWecomIds) {
                if (managedWecomIds.contains(current) && !desiredWecomIds.contains(current)) {
                    removeIds.add(current);
                }
            }
            if (addIds.isEmpty() && removeIds.isEmpty()) {
                counters.skipped++;
                continue;
            }
            TaskExecutionResult outcome = enqueueAndExecuteSyncPush(tenantId, corpId, client, contact,
                    addIds, removeIds, "MANUAL", actorUserId);
            if (outcome.succeeded) {
                counters.pushed++;
            } else {
                counters.recordFailure(outcome.errorMasked);
            }
        }
    }

    private TaskExecutionResult enqueueAndExecuteSyncPush(long tenantId, String corpId, WecomTagClient client,
                                                          WecomLinkedContactForTagSync contact,
                                                          List<String> addIds, List<String> removeIds,
                                                          String triggerSource, Long actorUserId) {
        Instant now = Instant.now();
        long taskId = repository.insertPushTask(tenantId, corpId, contact.customerId(), contact.wecomContactId(),
                contact.wecomUserid(), contact.externalUserid(), toJsonArray(addIds), toJsonArray(removeIds),
                DEFAULT_MAX_ATTEMPTS, null, triggerSource, actorUserId);
        WecomCustomerTagPushTask task = repository.findPushTaskById(tenantId, taskId).orElseThrow();
        boolean claimed = repository.claimPushTask(tenantId, taskId, task.attempts(), now,
                now.plusSeconds(taskLeaseSeconds)) == 1;
        if (!claimed) {
            return new TaskExecutionResult(false, "推送任务认领冲突");
        }
        return runClaimedTask(task, task.attempts() + 1, client, contact.tagSnapshotJson(), actorUserId);
    }

    private boolean executeClaimedOrPendingTask(WecomCustomerTagPushTask task, WecomTagClient client,
                                                Long actorUserId) {
        Instant now = Instant.now();
        if (repository.claimPushTask(task.tenantId(), task.id(), task.attempts(), now,
                now.plusSeconds(taskLeaseSeconds)) != 1) {
            return false;
        }
        String currentSnapshot = repository.findLinkedContactsByCustomerId(
                        task.tenantId(), task.corpId(), task.customerId()).stream()
                .filter(c -> c.wecomContactId() == task.wecomContactId())
                .map(WecomLinkedContactForTagSync::tagSnapshotJson)
                .findFirst().orElse(null);
        runClaimedTask(task, task.attempts() + 1, client, currentSnapshot, actorUserId);
        return true;
    }

    private TaskExecutionResult runClaimedTask(WecomCustomerTagPushTask task, int newAttempts,
                                               WecomTagClient client, String currentSnapshotJson, Long actorUserId) {
        List<String> addIds = new ArrayList<>(parseWecomTagIds(task.addTagIdsJson()));
        List<String> removeIds = new ArrayList<>(parseWecomTagIds(task.removeTagIdsJson()));
        try {
            client.markCustomerTags(task.wecomUserid(), task.externalUserid(), addIds, removeIds);
            repository.finishPushTask(task.tenantId(), task.id(), newAttempts,
                    WecomCustomerTagPushTask.STATUS_SUCCEEDED, null, null);
            Set<String> updatedSnapshot = parseWecomTagIds(currentSnapshotJson);
            updatedSnapshot.addAll(addIds);
            updatedSnapshot.removeAll(removeIds);
            repository.updateContactTagSnapshot(task.tenantId(), task.wecomContactId(), toJsonArray(updatedSnapshot));
            auditService.record(task.tenantId(), actorUserId, AuditService.ACTION_WECOM_CUSTOMER_TAG_PUSH,
                    "CUSTOMER", Long.toString(task.customerId()), null,
                    Map.of("corpId", task.corpId(), "taskId", task.id(), "wecomUserid", task.wecomUserid(),
                            "externalUseridMasked", maskExternalUserid(task.externalUserid()),
                            "addTagIds", addIds, "removeTagIds", removeIds, "adapterName", client.name()),
                    task.triggerSource());
            return new TaskExecutionResult(true, null);
        } catch (RuntimeException ex) {
            String error = maskError(ex);
            boolean retryable = newAttempts < task.maxAttempts();
            String nextStatus = retryable
                    ? WecomCustomerTagPushTask.STATUS_RETRYABLE : WecomCustomerTagPushTask.STATUS_FAILED;
            Instant nextRetryAt = retryable
                    ? Instant.now().plusSeconds(30L * (1L << Math.min(newAttempts - 1, 5))) : null;
            repository.finishPushTask(task.tenantId(), task.id(), newAttempts, nextStatus, nextRetryAt, error);
            return new TaskExecutionResult(false, error);
        }
    }

    // ========== 辅助方法 ==========

    private List<WecomLinkedContactForTagSync> filterVisibleContacts(long tenantId, CurrentUser user,
                                                                     List<WecomLinkedContactForTagSync> contacts) {
        if (contacts.isEmpty() || user == null) {
            return contacts;
        }
        DataScope scope = customerScopeService.scope(user);
        if (customerScopeService.isEmptyScope(scope)) {
            return List.of();
        }
        if (scope.type() == DataScope.ScopeType.ALL) {
            return contacts;
        }
        Set<Long> candidateCustomerIds = new LinkedHashSet<>();
        for (WecomLinkedContactForTagSync contact : contacts) {
            candidateCustomerIds.add(contact.customerId());
        }
        Set<Long> visibleCustomerIds = customerService.filterVisibleCustomerIds(tenantId, user, candidateCustomerIds);
        if (visibleCustomerIds.isEmpty()) {
            return List.of();
        }
        return contacts.stream()
                .filter(contact -> visibleCustomerIds.contains(contact.customerId()))
                .toList();
    }

    private List<DictionaryItemResponse> listCustomerTagItems(long tenantId) {
        try {
            return dictionaryService.listItemsByDictCode(tenantId, CustomerTagAdminService.CUSTOMER_TAGS_DICT_CODE);
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.NOT_FOUND) {
                return List.of();
            }
            throw ex;
        }
    }

    private List<WecomTagMapping> activeMappings(long tenantId, String corpId) {
        return repository.findMappings(tenantId, corpId).stream()
                .filter(m -> WecomTagMapping.STATUS_ACTIVE.equals(m.syncStatus())
                        && m.wecomTagId() != null && !m.wecomTagId().isBlank())
                .toList();
    }

    private Map<String, String> crmToWecomMap(List<WecomTagMapping> activeMappings) {
        Map<String, String> map = new LinkedHashMap<>();
        for (WecomTagMapping mapping : activeMappings) {
            map.put(mapping.crmTagCode(), mapping.wecomTagId());
        }
        return map;
    }

    private WecomTagClient requireClient(long tenantId) {
        WecomTagClient client = clientProvider.client(tenantId);
        if (client == null) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "企微标签双向同步未配置或当前租户未启用");
        }
        return client;
    }

    private String requireCorpId(WecomTagClient client) {
        String corpId = client.corpId();
        if (corpId == null || corpId.isBlank()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "企微企业 ID 尚未配置");
        }
        return corpId.strip();
    }

    private String resolveCorpId(WecomTagClient client) {
        if (client != null && client.corpId() != null && !client.corpId().isBlank()) {
            return client.corpId().strip();
        }
        return officialProperties.corpId() == null ? "" : officialProperties.corpId().strip();
    }

    private String normalizeDirection(String requested) {
        if (requested == null || requested.isBlank()) {
            return WecomTagSyncRun.DIRECTION_BIDIRECTIONAL;
        }
        String normalized = requested.strip().toUpperCase(Locale.ROOT);
        if (!VALID_DIRECTIONS.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同步方向仅支持 PULL、PUSH 或 BIDIRECTIONAL");
        }
        return normalized;
    }

    private WecomTagSyncRunResponse loadRunResponse(long tenantId, long runId) {
        return toRunResponse(repository.findRunById(tenantId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "标签同步批次不存在")));
    }

    private WecomTagMappingItemResponse toMappingItemResponse(DictionaryItemResponse crmTag, WecomTagMapping mapping) {
        if (mapping == null) {
            return new WecomTagMappingItemResponse(
                    crmTag.optionCode(), crmTag.optionLabel(), crmTag.status(),
                    null, null, null, null, STATUS_UNMAPPED, null);
        }
        return new WecomTagMappingItemResponse(
                crmTag.optionCode(), crmTag.optionLabel(), crmTag.status(),
                mapping.wecomGroupId(), mapping.wecomGroupName(),
                mapping.wecomTagId(), mapping.wecomTagName(),
                mapping.syncStatus(), mapping.lastSyncedAt());
    }

    private WecomTagSyncRunResponse toRunResponse(WecomTagSyncRun run) {
        return new WecomTagSyncRunResponse(
                run.id(), run.corpId(), run.syncScope(), run.direction(), run.triggerSource(),
                run.adapterName(), run.status(), run.pulledCount(), run.pushedCount(),
                run.skippedCount(), run.failedCount(), run.errorMasked(), run.startedAt(), run.finishedAt());
    }

    private WecomTagPushTaskResponse toTaskResponse(WecomCustomerTagPushTask task) {
        return new WecomTagPushTaskResponse(
                task.id(), task.corpId(), task.customerId(),
                maskExternalUserid(task.externalUserid()), task.wecomUserid(),
                parseWecomTagIds(task.addTagIdsJson()).size(),
                parseWecomTagIds(task.removeTagIdsJson()).size(),
                task.status(), task.attempts(), task.maxAttempts(), task.triggerSource(),
                task.errorMasked(), task.nextRetryAt(), task.updatedAt());
    }

    private void recordPushIdempotency(long tenantId, long actorUserId, String idempotencyKey, String payload,
                                       WecomCustomerTagPushResponse response) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        String summary = response.matchedContacts() + ":" + response.pushedCount() + ":"
                + response.skippedCount() + ":" + response.failedCount();
        idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_CUSTOMER_PUSH, actorUserId, idempotencyKey,
                "WECOM_CUSTOMER_TAG_PUSH", summary, payload);
    }

    private WecomCustomerTagPushResponse decodePushReplay(long customerId, String adapterName,
                                                          boolean officialEnabled, String resourceId) {
        String[] parts = resourceId == null ? new String[0] : resourceId.split(":");
        int matched = parts.length > 0 ? parseIntSafe(parts[0]) : 0;
        int pushed = parts.length > 1 ? parseIntSafe(parts[1]) : 0;
        int skipped = parts.length > 2 ? parseIntSafe(parts[2]) : 0;
        int failed = parts.length > 3 ? parseIntSafe(parts[3]) : 0;
        return new WecomCustomerTagPushResponse(
                customerId, adapterName, officialEnabled, matched, pushed, skipped, failed, null);
    }

    private int parseIntSafe(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private Map<String, Object> mappingSnapshot(WecomTagMapping mapping) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("corpId", mapping.corpId());
        map.put("crmTagCode", mapping.crmTagCode());
        map.put("wecomGroupId", mapping.wecomGroupId());
        map.put("wecomGroupName", mapping.wecomGroupName());
        map.put("wecomTagId", mapping.wecomTagId());
        map.put("wecomTagName", mapping.wecomTagName());
        map.put("syncStatus", mapping.syncStatus());
        return map;
    }

    private Map<String, Object> runAuditSnapshot(String corpId, String scope, String direction, String triggerSource,
                                                 String adapterName, String status, SyncCounters counters) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("corpId", corpId);
        map.put("syncScope", scope);
        map.put("direction", direction);
        map.put("triggerSource", triggerSource);
        map.put("adapterName", adapterName);
        map.put("status", status);
        map.put("pulledCount", counters.pulled);
        map.put("pushedCount", counters.pushed);
        map.put("skippedCount", counters.skipped);
        map.put("failedCount", counters.failed);
        return map;
    }

    private String toJsonArray(Iterable<String> items) {
        List<String> list = new ArrayList<>();
        if (items != null) {
            for (String item : items) {
                if (item != null && !item.isBlank()) {
                    list.add(item);
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String generateTagCode() {
        return "TAG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private String normalizeLabelKey(String label) {
        return label == null ? "" : label.strip().toLowerCase(Locale.ROOT);
    }

    private int safeOrder(Integer sortNo) {
        return sortNo == null || sortNo < 0 ? 0 : sortNo;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String maskError(RuntimeException ex) {
        if (ex instanceof BusinessException business && business.getMessage() != null) {
            String message = business.getMessage();
            return message.length() <= 500 ? message : message.substring(0, 500);
        }
        return "企微标签同步处理失败，未包含上游凭证或敏感参数";
    }

    private static final class SyncCounters {
        private int pulled;
        private int pushed;
        private int skipped;
        private int failed;
        private String firstError;

        private void recordFailure(String errorMasked) {
            this.failed++;
            if (this.firstError == null) {
                this.firstError = errorMasked;
            }
        }

        private String finalStatus() {
            if (failed == 0) {
                return WecomTagSyncRun.STATUS_SUCCESS;
            }
            if (pulled > 0 || pushed > 0) {
                return WecomTagSyncRun.STATUS_PARTIAL;
            }
            return WecomTagSyncRun.STATUS_ERROR;
        }
    }

    private record TaskExecutionResult(boolean succeeded, String errorMasked) {
    }
}
