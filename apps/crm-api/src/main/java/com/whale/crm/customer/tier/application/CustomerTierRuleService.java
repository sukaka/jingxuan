package com.whale.crm.customer.tier.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.tier.api.dto.CustomerTierEvaluationResponse;
import com.whale.crm.customer.tier.api.dto.CustomerTierRuleRequest;
import com.whale.crm.customer.tier.api.dto.CustomerTierRuleResponse;
import com.whale.crm.customer.tier.api.dto.CustomerTierTrialRequest;
import com.whale.crm.customer.tier.domain.CustomerTierLevel;
import com.whale.crm.customer.tier.domain.CustomerTierMetrics;
import com.whale.crm.customer.tier.domain.CustomerTierRepository;
import com.whale.crm.customer.tier.domain.CustomerTierRule;
import com.whale.crm.customer.tier.domain.TierLevelDefinition;
import com.whale.crm.customer.tier.domain.TierMetricDefinition;
import com.whale.crm.customer.tier.domain.TierWindow;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CustomerTierRuleService {
    private static final String CREATE_SCOPE = "customer:tier:rule:create";
    private static final String UPDATE_SCOPE = "customer:tier:rule:update";
    private static final String ACTIVATE_SCOPE = "customer:tier:rule:activate";
    private static final String RETIRE_SCOPE = "customer:tier:rule:retire";

    private final CustomerTierRepository repository;
    private final TierEvaluationService evaluationService;
    private final CustomerTierConfigMapper configMapper;
    private final CustomerScopeSupport scopeSupport;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public CustomerTierRuleService(CustomerTierRepository repository, TierEvaluationService evaluationService,
                                   CustomerTierConfigMapper configMapper, CustomerScopeSupport scopeSupport,
                                   IdempotencyService idempotencyService, AuditService auditService,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.evaluationService = evaluationService;
        this.configMapper = configMapper;
        this.scopeSupport = scopeSupport;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<CustomerTierRuleResponse> list(long tenantId) {
        return repository.findRules(tenantId).stream().map(configMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CustomerTierRuleResponse detail(long tenantId, long ruleId) {
        return configMapper.toResponse(requireRule(tenantId, ruleId));
    }

    @Transactional
    public CustomerTierRuleResponse create(long tenantId, CurrentUser user, CustomerTierRuleRequest request,
                                           String idempotencyKey) {
        requireKey(idempotencyKey);
        String payload = canonical(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, CREATE_SCOPE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            return detail(tenantId, Long.parseLong(replay.orElseThrow().getResourceId()));
        }
        RuleDefinition definition = validate(request);
        int versionNo = repository.nextVersionNo(tenantId, definition.ruleCode());
        CustomerTierRule candidate = new CustomerTierRule(null, tenantId, definition.ruleCode(), versionNo,
                definition.ruleName(), definition.zoneId(), CustomerTierRule.DRAFT,
                configMapper.writeMetrics(definition.metrics()), user.userId(), null, null, null, null, 0);
        CustomerTierRule created;
        try {
            created = repository.insertRule(candidate);
        } catch (DuplicateKeyException ex) {
            throw conflict("相同规则版本已存在，请刷新后重试");
        }
        insertLevels(tenantId, created.id(), definition.levels());
        auditService.record(tenantId, user.userId(), "CUSTOMER_TIER_RULE_CREATE", "CUSTOMER_TIER_RULE",
                Long.toString(created.id()), null, configMapper.toResponse(created), null);
        idempotencyService.recordSuccess(tenantId, CREATE_SCOPE, idempotencyKey, "CUSTOMER_TIER_RULE",
                Long.toString(created.id()), payload);
        return configMapper.toResponse(created);
    }

    @Transactional
    public CustomerTierRuleResponse update(long tenantId, CurrentUser user, long ruleId,
                                           CustomerTierRuleRequest request, long version, String idempotencyKey) {
        requireKey(idempotencyKey);
        String payload = ruleId + "|" + version + "|" + canonical(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, UPDATE_SCOPE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            return detail(tenantId, ruleId);
        }
        CustomerTierRule existing = repository.findRuleForUpdate(tenantId, ruleId)
                .orElseThrow(() -> notFound("分级规则不存在"));
        if (existing.version() != version) {
            throw conflict("规则已被他人修改，请刷新后重试");
        }
        if (!CustomerTierRule.DRAFT.equals(existing.status())) {
            throw conflict("已激活或已退役的规则版本配置冻结，不能修改");
        }
        RuleDefinition definition = validate(request);
        if (!existing.ruleCode().equals(definition.ruleCode())) {
            throw validation("规则代码创建后不可修改，请新建规则版本");
        }
        CustomerTierRuleResponse beforeResponse = configMapper.toResponse(existing);
        CustomerTierRule changed = new CustomerTierRule(existing.id(), tenantId, existing.ruleCode(),
                existing.versionNo(), definition.ruleName(), definition.zoneId(), existing.status(),
                configMapper.writeMetrics(definition.metrics()), existing.createdBy(), existing.activatedAt(),
                existing.retiredAt(), existing.createdAt(), existing.updatedAt(), version);
        if (repository.updateDraftRule(changed) != 1) {
            throw conflict("规则已被他人修改，请刷新后重试");
        }
        repository.deleteLevels(tenantId, ruleId);
        insertLevels(tenantId, ruleId, definition.levels());
        CustomerTierRule after = requireRule(tenantId, ruleId);
        auditService.record(tenantId, user.userId(), "CUSTOMER_TIER_RULE_UPDATE", "CUSTOMER_TIER_RULE",
                Long.toString(ruleId), beforeResponse, configMapper.toResponse(after), null);
        idempotencyService.recordSuccess(tenantId, UPDATE_SCOPE, idempotencyKey, "CUSTOMER_TIER_RULE",
                Long.toString(ruleId), payload);
        return configMapper.toResponse(after);
    }

    @Transactional
    public CustomerTierRuleResponse activate(long tenantId, CurrentUser user, long ruleId, long version,
                                             String idempotencyKey) {
        requireKey(idempotencyKey);
        String payload = ruleId + "|" + version;
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, ACTIVATE_SCOPE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            return detail(tenantId, ruleId);
        }
        CustomerTierRule previousActive = repository.findActiveRuleForUpdate(tenantId).orElse(null);
        CustomerTierRule target = repository.findRuleForUpdate(tenantId, ruleId)
                .orElseThrow(() -> notFound("分级规则不存在"));
        if (target.version() != version || !CustomerTierRule.DRAFT.equals(target.status())) {
            throw conflict("仅指定版本的 DRAFT 规则可激活");
        }
        configMapper.metrics(target);
        Instant now = Instant.now();
        if (previousActive != null) {
            if (repository.retireRule(tenantId, previousActive.id(), previousActive.version(), now) != 1) {
                throw conflict("当前 ACTIVE 规则已变化，请刷新后重试");
            }
        }
        try {
            if (repository.activateRule(tenantId, ruleId, version, now) != 1) {
                throw conflict("规则激活冲突，请刷新后重试");
            }
        } catch (DuplicateKeyException ex) {
            throw conflict("每租户只能有一个 ACTIVE 规则版本");
        }
        repository.markAllPendingStale(tenantId, "分级规则版本已变化", now);
        CustomerTierRule after = requireRule(tenantId, ruleId);
        auditService.record(tenantId, user.userId(), "CUSTOMER_TIER_RULE_ACTIVATE", "CUSTOMER_TIER_RULE",
                Long.toString(ruleId), previousActive, after, null);
        idempotencyService.recordSuccess(tenantId, ACTIVATE_SCOPE, idempotencyKey, "CUSTOMER_TIER_RULE",
                Long.toString(ruleId), payload);
        return configMapper.toResponse(after);
    }

    @Transactional
    public CustomerTierRuleResponse retire(long tenantId, CurrentUser user, long ruleId, long version,
                                           String idempotencyKey) {
        requireKey(idempotencyKey);
        String payload = ruleId + "|" + version;
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, RETIRE_SCOPE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            return detail(tenantId, ruleId);
        }
        CustomerTierRule target = repository.findRuleForUpdate(tenantId, ruleId)
                .orElseThrow(() -> notFound("分级规则不存在"));
        if (target.version() != version || !CustomerTierRule.ACTIVE.equals(target.status())) {
            throw conflict("仅指定版本的 ACTIVE 规则可退役");
        }
        Instant now = Instant.now();
        if (repository.retireRule(tenantId, ruleId, version, now) != 1) {
            throw conflict("规则退役冲突，请刷新后重试");
        }
        repository.markAllPendingStale(tenantId, "分级规则版本已退役", now);
        CustomerTierRule after = requireRule(tenantId, ruleId);
        auditService.record(tenantId, user.userId(), "CUSTOMER_TIER_RULE_RETIRE", "CUSTOMER_TIER_RULE",
                Long.toString(ruleId), target, after, null);
        idempotencyService.recordSuccess(tenantId, RETIRE_SCOPE, idempotencyKey, "CUSTOMER_TIER_RULE",
                Long.toString(ruleId), payload);
        return configMapper.toResponse(after);
    }

    @Transactional(readOnly = true)
    public CustomerTierEvaluationResponse trial(long tenantId, CurrentUser user, long ruleId,
                                                CustomerTierTrialRequest request) {
        CustomerTierRule rule = requireRule(tenantId, ruleId);
        scopeSupport.requireVisible(tenantId, request.customerId(), user);
        return evaluate(tenantId, request.customerId(), rule,
                request.asOf() == null ? Instant.now() : request.asOf(), "TRIAL");
    }

    @Transactional(readOnly = true)
    public List<CustomerTierEvaluationResponse> sample(long tenantId, CurrentUser user, long ruleId, int limit) {
        CustomerTierRule rule = requireRule(tenantId, ruleId);
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return List.of();
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        Instant now = Instant.now();
        return repository.findScopedCustomerIds(tenantId, query.scopeType(), query.teamIds(), query.selfId(), limit)
                .stream().map(customerId -> evaluate(tenantId, customerId, rule, now, "SAMPLE")).toList();
    }

    private CustomerTierEvaluationResponse evaluate(long tenantId, long customerId, CustomerTierRule rule,
                                                    Instant asOf, String source) {
        TierWindow window = evaluationService.window(asOf, ZoneId.of(rule.zoneId()));
        CustomerTierMetrics values = repository.loadMetrics(tenantId, customerId,
                window.startInclusive(), window.endExclusive());
        TierEvaluation result = evaluationService.evaluate(configMapper.levels(rule), configMapper.metrics(rule), values);
        return configMapper.evaluation(customerId, rule, window, values, result, source);
    }

    private RuleDefinition validate(CustomerTierRuleRequest request) {
        if (request == null || request.ruleCode() == null
                || !request.ruleCode().matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw validation("规则代码格式无效");
        }
        String ruleName = request.ruleName() == null ? "" : request.ruleName().strip();
        if (ruleName.isEmpty() || ruleName.length() > 128) {
            throw validation("规则名称不能为空且最长 128 字符");
        }
        String zoneId = request.timezone() == null ? "" : request.timezone().strip();
        try {
            ZoneId.of(zoneId);
        } catch (DateTimeException ex) {
            throw validation("规则时区无效");
        }
        List<TierLevelDefinition> levels = new ArrayList<>();
        if (request.levels() != null) {
            for (CustomerTierRuleRequest.Level level : request.levels()) {
                levels.add(new TierLevelDefinition(level.tierCode(), level.tierName().strip(),
                        level.sortNo(), level.minScore() == null ? java.math.BigDecimal.ZERO : level.minScore(),
                        level.minConsumptionFen()));
            }
        }
        List<TierMetricDefinition> metrics = new ArrayList<>();
        if (request.metrics() != null) {
            for (CustomerTierRuleRequest.Metric metric : request.metrics()) {
                Map<String, Long> thresholds = new LinkedHashMap<>();
                if (metric.thresholds() != null) {
                    for (CustomerTierRuleRequest.Threshold threshold : metric.thresholds()) {
                        if (thresholds.put(threshold.tierCode(), threshold.value()) != null) {
                            throw validation("同一指标存在重复等级阈值");
                        }
                    }
                }
                metrics.add(new TierMetricDefinition(metric.metricCode(), metric.direction(),
                        metric.weight(), thresholds));
            }
        }
        evaluationService.validate(levels, metrics);
        return new RuleDefinition(request.ruleCode(), ruleName, zoneId, List.copyOf(levels), List.copyOf(metrics));
    }

    private void insertLevels(long tenantId, long ruleId, List<TierLevelDefinition> levels) {
        for (TierLevelDefinition level : levels) {
            repository.insertLevel(new CustomerTierLevel(null, tenantId, ruleId, level.tierCode(),
                    level.tierName(), level.sortNo(), level.minScore(), level.minConsumptionFen()));
        }
    }

    private CustomerTierRule requireRule(long tenantId, long ruleId) {
        return repository.findRule(tenantId, ruleId).orElseThrow(() -> notFound("分级规则不存在"));
    }

    private String canonical(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw validation("请求无法序列化");
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw validation("Idempotency-Key 不能为空且最长 128 字符");
        }
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private record RuleDefinition(String ruleCode, String ruleName, String zoneId,
                                  List<TierLevelDefinition> levels, List<TierMetricDefinition> metrics) {
    }
}
