package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.StewardDirectory;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderAssignRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanStewardAliasRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanStewardAliasSaveResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanStewardAliasView;
import com.whale.crm.integration.youzan.api.dto.YouzanStewardOptionView;
import com.whale.crm.integration.youzan.domain.YouzanOrderAssignment;
import com.whale.crm.integration.youzan.domain.YouzanOrderAssignmentRepository;
import com.whale.crm.integration.youzan.domain.YouzanStewardAlias;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 有赞订单备注解析、按「账号」自动/人工分配管家及账号别名映射应用服务。
 * 业务边界：
 * 1. 仅负责有赞订单层（crm_youzan_order_assignment）的归属管家分配与数据隔离，绝不覆盖已有 CRM 客户归属；
 * 2. 优先匹配启用的账号别名映射表，其次精确匹配在职管家姓名（MANAGER/MANAGER_LEAD）；
 * 3. 人工分配结果（assignment_source=MANUAL）在后续订单同步刷新时不被自动覆盖。
 */
@Service
public class YouzanOrderAssignmentService {

    public static final String STATUS_ASSIGNED = "ASSIGNED";
    public static final String STATUS_UNMATCHED = "UNMATCHED";
    public static final String STATUS_AMBIGUOUS = "AMBIGUOUS";
    public static final String STATUS_NO_ACCOUNT = "NO_ACCOUNT";

    public static final String SOURCE_AUTO_ALIAS = "AUTO_ALIAS";
    public static final String SOURCE_AUTO_DISPLAY_NAME = "AUTO_DISPLAY_NAME";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_NONE = "NONE";

    public static final String ALIAS_STATUS_ACTIVE = "ACTIVE";
    public static final String ALIAS_STATUS_INACTIVE = "INACTIVE";

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> VALID_ALIAS_STATUSES = Set.of(ALIAS_STATUS_ACTIVE, ALIAS_STATUS_INACTIVE);
    private static final String IDEMPOTENCY_SCOPE_ALIAS = "youzan.steward.alias";
    private static final String IDEMPOTENCY_SCOPE_ASSIGN = "youzan.order.steward.assign";

    private final YouzanOrderAssignmentRepository repository;
    private final YouzanTradeMemoParser memoParser;
    private final UserRepository userRepository;
    private final StewardDirectory stewardDirectory;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public YouzanOrderAssignmentService(YouzanOrderAssignmentRepository repository,
                                        YouzanTradeMemoParser memoParser,
                                        UserRepository userRepository,
                                        StewardDirectory stewardDirectory,
                                        IdempotencyService idempotencyService,
                                        AuditService auditService,
                                        ObjectMapper objectMapper,
                                        PlatformTransactionManager transactionManager) {
        this(repository, memoParser, userRepository, stewardDirectory, idempotencyService,
                auditService, objectMapper, transactionManager, Clock.system(BUSINESS_ZONE));
    }

    public YouzanOrderAssignmentService(YouzanOrderAssignmentRepository repository,
                                        YouzanTradeMemoParser memoParser,
                                        UserRepository userRepository,
                                        StewardDirectory stewardDirectory,
                                        IdempotencyService idempotencyService,
                                        AuditService auditService,
                                        ObjectMapper objectMapper,
                                        PlatformTransactionManager transactionManager,
                                        Clock clock) {
        this.repository = repository;
        this.memoParser = memoParser;
        this.userRepository = userRepository;
        this.stewardDirectory = stewardDirectory;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock != null ? clock : Clock.system(BUSINESS_ZONE);
    }

    /**
     * 从有赞官方原始订单报文（youzan.trade.get / youzan.trades.sold.get）解析展示信息与 trade_memo，
     * 并执行自动管家分配。若该订单已人工指派，则保留人工分配结果，仅更新订单状态与备注解析字段。
     */
    public Optional<YouzanOrderAssignment> syncFromProviderPayload(long tenantId, String shopId,
                                                                   String externalOrderNo, Long latestEventId,
                                                                   String rawProviderPayload) {
        String cleanShopId = trimToNull(shopId);
        String cleanOrderNo = trimToNull(externalOrderNo);
        if (cleanShopId == null || cleanOrderNo == null || rawProviderPayload == null || rawProviderPayload.isBlank()) {
            return Optional.empty();
        }
        YouzanTradeMemoParser.ParsedOrderDetails parsed = memoParser.parseProviderPayload(rawProviderPayload);
        if (!parsed.hasProviderOrder()) {
            return Optional.empty();
        }

        return Optional.ofNullable(transactionTemplate.execute(status -> {
            YouzanOrderAssignment existing = repository.findByOrder(tenantId, cleanShopId, cleanOrderNo).orElse(null);
            boolean preserveExistingMemo = existing != null && !parsed.hasRemarkNode()
                    && existing.tradeMemoMasked() != null;

            YouzanTradeMemoParser.ParsedMemo memo = parsed.memo();
            String tradeMemoMasked = preserveExistingMemo ? existing.tradeMemoMasked() : memo.tradeMemoMasked();
            String performanceOwner = preserveExistingMemo ? existing.memoPerformanceOwner() : memo.performanceOwner();
            String channel = preserveExistingMemo ? existing.memoChannel() : memo.channel();
            String customerType = preserveExistingMemo ? existing.memoCustomerType() : memo.customerType();
            String source = preserveExistingMemo ? existing.memoSource() : memo.source();
            String accountName = preserveExistingMemo ? existing.memoAccountName() : memo.accountName();
            String accountKey = preserveExistingMemo ? existing.memoAccountKey() : memo.accountKey();
            String institutionName = preserveExistingMemo ? existing.memoInstitutionName() : memo.institutionName();
            String extraNote = preserveExistingMemo ? existing.memoExtraNote() : memo.extraNote();

            ResolvedSteward resolved;
            if (existing != null && SOURCE_MANUAL.equals(existing.assignmentSource())
                    && existing.assignedStewardId() != null) {
                resolved = new ResolvedSteward(
                        existing.assignedStewardId(),
                        existing.assignedTeamId(),
                        existing.assignmentStatus(),
                        existing.assignmentSource(),
                        existing.assignmentReason(),
                        existing.assignedBy(),
                        existing.assignedAt());
            } else if (preserveExistingMemo) {
                resolved = new ResolvedSteward(
                        existing.assignedStewardId(),
                        existing.assignedTeamId(),
                        existing.assignmentStatus(),
                        existing.assignmentSource(),
                        existing.assignmentReason(),
                        existing.assignedBy(),
                        existing.assignedAt());
            } else {
                resolved = resolveAutoSteward(tenantId, accountName, accountKey);
            }

            YouzanOrderAssignment toSave = new YouzanOrderAssignment(
                    null,
                    tenantId,
                    cleanShopId,
                    cleanOrderNo,
                    latestEventId,
                    parsed.providerOrderStatus(),
                    parsed.providerStatusText(),
                    parsed.payWayDesc(),
                    parsed.orderedAt(),
                    parsed.consignedAt(),
                    parsed.buyerFormNameMasked(),
                    parsed.buyerFormMobileMasked(),
                    tradeMemoMasked,
                    performanceOwner,
                    channel,
                    customerType,
                    source,
                    accountName,
                    accountKey,
                    institutionName,
                    extraNote,
                    resolved.stewardUserId(),
                    resolved.teamId(),
                    resolved.status(),
                    resolved.source(),
                    resolved.reason(),
                    resolved.assignedBy(),
                    resolved.assignedAt(),
                    null,
                    null,
                    null);
            repository.upsertOrderAssignment(tenantId, toSave);
            return repository.findByOrder(tenantId, cleanShopId, cleanOrderNo).orElse(toSave);
        }));
    }

    @Transactional(readOnly = true)
    public Optional<YouzanOrderAssignment> findByOrder(long tenantId, String shopId, String externalOrderNo) {
        if (shopId == null || shopId.isBlank() || externalOrderNo == null || externalOrderNo.isBlank()) {
            return Optional.empty();
        }
        return repository.findByOrder(tenantId, shopId.strip(), externalOrderNo.strip());
    }

    @Transactional(readOnly = true)
    public Map<String, YouzanOrderAssignment> findByOrdersMap(long tenantId, String shopId,
                                                              Collection<String> externalOrderNos) {
        if (shopId == null || shopId.isBlank() || externalOrderNos == null || externalOrderNos.isEmpty()) {
            return Map.of();
        }
        List<YouzanOrderAssignment> rows = repository.findByOrders(tenantId, shopId.strip(), externalOrderNos);
        Map<String, YouzanOrderAssignment> result = new HashMap<>();
        if (rows != null) {
            for (YouzanOrderAssignment row : rows) {
                if (row != null && row.externalOrderNo() != null) {
                    result.put(row.externalOrderNo(), row);
                }
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<Long, UserRepository.StewardOptionRow> loadStaffProfiles(long tenantId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        List<UserRepository.StewardOptionRow> rows = userRepository.findStaffProfilesByIds(tenantId, distinctIds);
        Map<Long, UserRepository.StewardOptionRow> result = new HashMap<>();
        if (rows != null) {
            for (UserRepository.StewardOptionRow row : rows) {
                if (row != null && row.id() != null) {
                    result.put(row.id(), row);
                }
            }
        }
        return result;
    }

    public ManualAssignResult assignOrderSteward(CurrentUser operator, String shopId, String externalOrderNo,
                                                 Long latestEventId, YouzanOrderAssignRequest request,
                                                 String idempotencyKey) {
        requireEmployee(operator);
        String key = requireKey(idempotencyKey);
        String cleanShopId = requireText(shopId, "店铺编号不能为空");
        String cleanOrderNo = requireText(externalOrderNo, "有赞订单号不能为空");
        if (request == null || request.stewardUserId() == null || request.stewardUserId() <= 0L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标归属管家不能为空");
        }
        long tenantId = operator.tenantId();
        SysUser targetSteward = requireAssignableSteward(tenantId, operator, request.stewardUserId());
        boolean saveAlias = Boolean.TRUE.equals(request.saveAlias());
        if (saveAlias && !operator.permissions().contains("integration:youzan:manage")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅具备有赞管理权限的管理员可同步保存账号别名映射");
        }

        String cleanReason = trimToNull(request.reason());
        if (cleanReason != null) {
            cleanReason = truncate(YouzanTradeMemoParser.maskMobiles(cleanReason), 500);
        }
        String effectiveReason = cleanReason != null ? cleanReason : "人工分配给 " + targetSteward.getDisplayName();
        String requestedAlias = trimToNull(request.aliasName());

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("shopId", cleanShopId);
        canonical.put("externalOrderNo", cleanOrderNo);
        canonical.put("stewardUserId", targetSteward.getId());
        canonical.put("reason", effectiveReason);
        canonical.put("version", request.version());
        canonical.put("saveAlias", saveAlias);
        canonical.put("aliasName", requestedAlias);
        String payload = json(canonical);

        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                tenantId, IDEMPOTENCY_SCOPE_ASSIGN, operator.userId(), key, payload);
        if (replay.isPresent()) {
            YouzanOrderAssignment current = repository.findByOrder(tenantId, cleanShopId, cleanOrderNo)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "有赞订单分配记录不存在"));
            return new ManualAssignResult(current, 0);
        }

        String finalReason = effectiveReason;
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            YouzanOrderAssignment existing = repository.findByOrderForUpdate(tenantId, cleanShopId, cleanOrderNo)
                    .orElse(null);
            YouzanOrderAssignment before = existing;
            if (existing == null) {
                YouzanOrderAssignment created = new YouzanOrderAssignment(
                        null, tenantId, cleanShopId, cleanOrderNo, latestEventId,
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null,
                        targetSteward.getId(), targetSteward.getTeamId(),
                        STATUS_ASSIGNED, SOURCE_MANUAL, finalReason, operator.userId(), now,
                        null, null, null);
                repository.upsertOrderAssignment(tenantId, created);
            } else {
                if (request.version() != null && !request.version().equals(existing.version())) {
                    throw new BusinessException(ErrorCode.CONFLICT, "有赞订单管家分配版本已变化，请刷新后重试");
                }
                int updated = repository.updateManualAssignment(tenantId, existing.id(), existing.version(),
                        targetSteward.getId(), targetSteward.getTeamId(),
                        STATUS_ASSIGNED, SOURCE_MANUAL, finalReason, operator.userId(), now);
                if (updated != 1) {
                    throw new BusinessException(ErrorCode.CONFLICT, "有赞订单管家分配版本冲突，请刷新后重试");
                }
            }

            YouzanOrderAssignment saved = repository.findByOrder(tenantId, cleanShopId, cleanOrderNo).orElseThrow();
            int backfilled = 0;
            if (saveAlias) {
                String aliasName = firstNonBlank(requestedAlias, saved.memoAccountName());
                if (aliasName != null) {
                    backfilled = upsertActiveAliasAndBackfill(tenantId, operator.userId(), aliasName,
                            targetSteward, "人工分配订单 " + cleanOrderNo + " 时自动沉淀别名", true, now);
                }
            }

            idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_ASSIGN, operator.userId(), key,
                    "YOUZAN_ORDER_ASSIGNMENT", saved.id().toString(), payload);
            auditService.record(tenantId, operator.userId(), AuditService.ACTION_YOUZAN_ORDER_STEWARD_ASSIGN,
                    "YOUZAN_ORDER", cleanShopId + ":" + cleanOrderNo,
                    assignmentAuditSnapshot(before), assignmentAuditSnapshot(saved), finalReason);
            return new ManualAssignResult(saved, backfilled);
        }));
    }

    @Transactional(readOnly = true)
    public List<YouzanStewardAliasView> listAliases(long tenantId, CurrentUser operator) {
        requireEmployee(operator);
        List<YouzanStewardAlias> rows = repository.findAliases(tenantId);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Long, UserRepository.StewardOptionRow> profiles = loadStaffProfiles(
                tenantId, rows.stream().map(YouzanStewardAlias::stewardUserId).toList());
        return rows.stream().map(row -> toAliasView(row, profiles.get(row.stewardUserId()))).toList();
    }

    public YouzanStewardAliasSaveResponse saveAlias(CurrentUser operator, YouzanStewardAliasRequest request,
                                                    String idempotencyKey) {
        requireEmployee(operator);
        if (!operator.permissions().contains("integration:youzan:manage")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "缺少有赞接入管理权限");
        }
        String key = requireKey(idempotencyKey);
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求体不能为空");
        }
        String aliasName = requireText(request.aliasName(), "有赞备注账号名不能为空");
        String aliasKey = YouzanTradeMemoParser.normalizeAccountKey(aliasName);
        if (aliasKey == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞备注账号名无效");
        }
        if (request.stewardUserId() == null || request.stewardUserId() <= 0L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标管家不能为空");
        }
        String status = normalizeAliasStatus(request.status());
        String remark = trimToNull(request.remark());
        if (remark != null) {
            remark = truncate(YouzanTradeMemoParser.maskMobiles(remark), 255);
        }
        boolean backfillPending = request.backfillPending() == null || request.backfillPending();

        long tenantId = operator.tenantId();
        SysUser targetSteward = requireEligibleSteward(tenantId, request.stewardUserId());

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("aliasName", aliasName);
        canonical.put("aliasKey", aliasKey);
        canonical.put("stewardUserId", targetSteward.getId());
        canonical.put("status", status);
        canonical.put("remark", remark);
        canonical.put("version", request.version());
        canonical.put("backfillPending", backfillPending);
        String payload = json(canonical);

        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                tenantId, IDEMPOTENCY_SCOPE_ALIAS, operator.userId(), key, payload);
        if (replay.isPresent()) {
            YouzanStewardAlias existing = repository.findAliasByKey(tenantId, aliasKey)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "账号别名映射不存在"));
            Map<Long, UserRepository.StewardOptionRow> profiles = loadStaffProfiles(
                    tenantId, List.of(existing.stewardUserId()));
            return new YouzanStewardAliasSaveResponse(toAliasView(existing, profiles.get(existing.stewardUserId())), 0);
        }

        String finalRemark = remark;
        try {
            return Objects.requireNonNull(transactionTemplate.execute(tx -> {
                Instant now = clock.instant();
                YouzanStewardAlias existing = repository.findAliasByKeyForUpdate(tenantId, aliasKey).orElse(null);
                if (existing == null) {
                    repository.insertAlias(tenantId, new YouzanStewardAlias(
                            null, tenantId, aliasName, aliasKey, targetSteward.getId(), status, finalRemark,
                            operator.userId(), operator.userId(), null, null, null));
                } else {
                    if (request.version() == null || !request.version().equals(existing.version())) {
                        throw new BusinessException(ErrorCode.CONFLICT, "账号别名映射版本已变化，请刷新后重试");
                    }
                    int updated = repository.updateAlias(tenantId, existing.id(), request.version(), aliasName,
                            aliasKey, targetSteward.getId(), status, finalRemark, operator.userId());
                    if (updated != 1) {
                        throw new BusinessException(ErrorCode.CONFLICT, "账号别名映射版本冲突，请刷新后重试");
                    }
                }
                YouzanStewardAlias saved = repository.findAliasByKey(tenantId, aliasKey).orElseThrow();
                int backfilled = 0;
                if (ALIAS_STATUS_ACTIVE.equals(status) && backfillPending) {
                    String backfillReason = "通过账号别名「" + aliasName + "」自动回填分配给 " + targetSteward.getDisplayName();
                    backfilled = repository.backfillPendingByAccountKey(
                            tenantId, aliasKey, targetSteward.getId(), targetSteward.getTeamId(),
                            truncate(backfillReason, 500), now);
                }
                idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_ALIAS, operator.userId(), key,
                        "YOUZAN_STEWARD_ALIAS", saved.id().toString(), payload);
                Map<String, Object> afterAudit = new LinkedHashMap<>();
                afterAudit.put("id", saved.id());
                afterAudit.put("aliasName", saved.aliasName());
                afterAudit.put("stewardUserId", saved.stewardUserId());
                afterAudit.put("status", saved.status());
                afterAudit.put("backfilledOrderCount", backfilled);
                auditService.record(tenantId, operator.userId(), AuditService.ACTION_YOUZAN_STEWARD_ALIAS_SAVE,
                        "YOUZAN_STEWARD_ALIAS", saved.id().toString(),
                        existing == null ? null : aliasAuditSnapshot(existing), afterAudit, finalRemark);
                Map<Long, UserRepository.StewardOptionRow> profiles = loadStaffProfiles(
                        tenantId, List.of(saved.stewardUserId()));
                return new YouzanStewardAliasSaveResponse(
                        toAliasView(saved, profiles.get(saved.stewardUserId())), backfilled);
            }));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "该账号别名映射已存在，请刷新后编辑");
        }
    }

    public void deleteAlias(CurrentUser operator, long aliasId) {
        requireEmployee(operator);
        if (!operator.permissions().contains("integration:youzan:manage")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "缺少有赞接入管理权限");
        }
        long tenantId = operator.tenantId();
        transactionTemplate.executeWithoutResult(tx -> {
            YouzanStewardAlias existing = repository.findAliasById(tenantId, aliasId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "账号别名映射不存在"));
            if (repository.deleteAlias(tenantId, aliasId) != 1) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "账号别名映射不存在");
            }
            auditService.record(tenantId, operator.userId(), AuditService.ACTION_YOUZAN_STEWARD_ALIAS_DELETE,
                    "YOUZAN_STEWARD_ALIAS", Long.toString(aliasId), aliasAuditSnapshot(existing), null, null);
        });
    }

    @Transactional(readOnly = true)
    public List<YouzanStewardOptionView> stewardOptions(long tenantId, CurrentUser operator) {
        requireEmployee(operator);
        return stewardDirectory.optionsFor(tenantId, operator).stream()
                .map(row -> new YouzanStewardOptionView(row.id(), row.displayName(), row.teamId(), row.teamName()))
                .toList();
    }

    private int upsertActiveAliasAndBackfill(long tenantId, long actorUserId, String aliasName,
                                             SysUser targetSteward, String remark,
                                             boolean backfillPending, Instant now) {
        String aliasKey = YouzanTradeMemoParser.normalizeAccountKey(aliasName);
        if (aliasKey == null) {
            return 0;
        }
        YouzanStewardAlias existing = repository.findAliasByKeyForUpdate(tenantId, aliasKey).orElse(null);
        if (existing == null) {
            repository.insertAlias(tenantId, new YouzanStewardAlias(
                    null, tenantId, truncate(aliasName.strip(), 64), aliasKey,
                    targetSteward.getId(), ALIAS_STATUS_ACTIVE, truncate(remark, 255),
                    actorUserId, actorUserId, null, null, null));
        } else {
            repository.updateAlias(tenantId, existing.id(), existing.version(),
                    truncate(aliasName.strip(), 64), aliasKey, targetSteward.getId(),
                    ALIAS_STATUS_ACTIVE, truncate(remark, 255), actorUserId);
        }
        YouzanStewardAlias saved = repository.findAliasByKey(tenantId, aliasKey).orElseThrow();
        int backfilled = 0;
        if (backfillPending) {
            String backfillReason = "通过账号别名「" + aliasName + "」自动回填分配给 " + targetSteward.getDisplayName();
            backfilled = repository.backfillPendingByAccountKey(
                    tenantId, aliasKey, targetSteward.getId(), targetSteward.getTeamId(),
                    truncate(backfillReason, 500), now);
        }
        auditService.record(tenantId, actorUserId, AuditService.ACTION_YOUZAN_STEWARD_ALIAS_SAVE,
                "YOUZAN_STEWARD_ALIAS", saved.id().toString(),
                existing == null ? null : aliasAuditSnapshot(existing),
                Map.of("id", saved.id(), "aliasName", saved.aliasName(),
                        "stewardUserId", saved.stewardUserId(), "status", saved.status(),
                        "backfilledOrderCount", backfilled),
                remark);
        return backfilled;
    }

    private ResolvedSteward resolveAutoSteward(long tenantId, String accountName, String accountKey) {
        if (accountKey == null || accountKey.isBlank()) {
            return new ResolvedSteward(null, null, STATUS_NO_ACCOUNT, SOURCE_NONE,
                    "商家备注中未包含「账号：」信息，待人工分配", null, null);
        }
        Instant now = clock.instant();
        Optional<YouzanStewardAlias> aliasOpt = repository.findAliasByKey(tenantId, accountKey);
        if (aliasOpt.isPresent() && ALIAS_STATUS_ACTIVE.equals(aliasOpt.get().status())) {
            YouzanStewardAlias alias = aliasOpt.get();
            Optional<SysUser> userOpt = userRepository.findById(tenantId, alias.stewardUserId());
            if (userOpt.isPresent() && stewardDirectory.isEligibleSteward(tenantId, userOpt.get())) {
                SysUser steward = userOpt.get();
                return new ResolvedSteward(
                        steward.getId(),
                        steward.getTeamId(),
                        STATUS_ASSIGNED,
                        SOURCE_AUTO_ALIAS,
                        truncate("通过账号别名「" + accountName + "」自动分配给 " + steward.getDisplayName(), 500),
                        null,
                        now);
            }
            return new ResolvedSteward(null, null, STATUS_UNMATCHED, SOURCE_NONE,
                    truncate("账号别名「" + accountName + "」映射的管家已停用或无管家角色，请重新配置别名或人工分配", 500),
                    null, null);
        }

        List<UserRepository.StewardOptionRow> allStewards = userRepository.findStewardOptions(
                tenantId, StewardDirectory.STEWARD_ROLE_CODES, null, null);
        List<UserRepository.StewardOptionRow> exactMatches = allStewards == null ? List.of() : allStewards.stream()
                .filter(row -> row != null && accountKey.equals(YouzanTradeMemoParser.normalizeAccountKey(row.displayName())))
                .toList();
        if (exactMatches.size() == 1) {
            UserRepository.StewardOptionRow matched = exactMatches.get(0);
            return new ResolvedSteward(
                    matched.id(),
                    matched.teamId(),
                    STATUS_ASSIGNED,
                    SOURCE_AUTO_DISPLAY_NAME,
                    truncate("通过管家姓名精确匹配「" + accountName + "」自动分配", 500),
                    null,
                    now);
        }
        if (exactMatches.size() > 1) {
            return new ResolvedSteward(null, null, STATUS_AMBIGUOUS, SOURCE_NONE,
                    truncate("账号「" + accountName + "」命中 " + exactMatches.size() + " 位同名在职管家，请配置别名或人工分配", 500),
                    null, null);
        }
        return new ResolvedSteward(null, null, STATUS_UNMATCHED, SOURCE_NONE,
                truncate("账号「" + accountName + "」未匹配到在职管家，请配置别名或人工分配", 500),
                null, null);
    }

    private SysUser requireEligibleSteward(long tenantId, long stewardUserId) {
        SysUser user = userRepository.findById(tenantId, stewardUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "目标管家不存在"));
        if (!stewardDirectory.isEligibleSteward(tenantId, user)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标管家必须为在职管家或管家主管");
        }
        return user;
    }

    private SysUser requireAssignableSteward(long tenantId, CurrentUser operator, long stewardUserId) {
        SysUser user = requireEligibleSteward(tenantId, stewardUserId);
        if (!stewardDirectory.canAssignTo(operator, user)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "目标管家超出当前操作人可分配数据范围");
        }
        return user;
    }

    private static void requireEmployee(CurrentUser operator) {
        if (operator == null || operator.isInstitutionUser() || !"EMPLOYEE".equals(operator.userType())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅内部员工可访问有赞订单分配与别名管理");
        }
    }

    private static String normalizeAliasStatus(String raw) {
        String clean = trimToNull(raw);
        if (clean == null) {
            return ALIAS_STATUS_ACTIVE;
        }
        String upper = clean.toUpperCase(java.util.Locale.ROOT);
        if (!VALID_ALIAS_STATUSES.contains(upper)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "别名映射状态仅支持 ACTIVE 或 INACTIVE");
        }
        return upper;
    }

    private static YouzanStewardAliasView toAliasView(YouzanStewardAlias row,
                                                      UserRepository.StewardOptionRow profile) {
        return new YouzanStewardAliasView(
                row.id(),
                row.aliasName(),
                row.aliasKey(),
                row.stewardUserId(),
                profile == null ? null : profile.displayName(),
                profile == null ? null : profile.teamId(),
                profile == null ? null : profile.teamName(),
                row.status(),
                row.remark(),
                row.createdAt(),
                row.updatedAt(),
                row.version());
    }

    private static Map<String, Object> aliasAuditSnapshot(YouzanStewardAlias alias) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", alias.id());
        snap.put("aliasName", alias.aliasName());
        snap.put("stewardUserId", alias.stewardUserId());
        snap.put("status", alias.status());
        return snap;
    }

    private static Map<String, Object> assignmentAuditSnapshot(YouzanOrderAssignment assignment) {
        if (assignment == null) {
            return null;
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("shopId", assignment.shopId());
        snap.put("externalOrderNo", assignment.externalOrderNo());
        snap.put("memoAccountName", assignment.memoAccountName());
        snap.put("assignedStewardId", assignment.assignedStewardId());
        snap.put("assignedTeamId", assignment.assignedTeamId());
        snap.put("assignmentStatus", assignment.assignmentStatus());
        snap.put("assignmentSource", assignment.assignmentSource());
        return snap;
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供1至128字符的幂等键");
        }
        return key.strip();
    }

    private static String requireText(String value, String message) {
        String clean = trimToNull(value);
        if (clean == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message);
        }
        return clean;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化请求失败", ex);
        }
    }

    private record ResolvedSteward(
            Long stewardUserId,
            Long teamId,
            String status,
            String source,
            String reason,
            Long assignedBy,
            Instant assignedAt) {
    }

    public record ManualAssignResult(
            YouzanOrderAssignment assignment,
            int backfilledAliasOrderCount) {
    }
}
