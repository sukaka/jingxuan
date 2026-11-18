package com.whale.crm.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.AiChatDelegationCustomerControlRequest;
import com.whale.crm.ai.api.dto.AiChatDelegationCustomerControlResponse;
import com.whale.crm.ai.api.dto.AiChatDelegationEmergencyStopRequest;
import com.whale.crm.ai.api.dto.AiChatDelegationPolicyResponse;
import com.whale.crm.ai.api.dto.AiChatDelegationRunResponse;
import com.whale.crm.ai.api.dto.AiChatDelegationStatusResponse;
import com.whale.crm.ai.api.dto.UpdateAiChatDelegationPolicyRequest;
import com.whale.crm.ai.domain.AiChatDelegationAttempt;
import com.whale.crm.ai.domain.AiChatDelegationCustomerControl;
import com.whale.crm.ai.domain.AiChatDelegationPolicy;
import com.whale.crm.ai.domain.AiChatDelegationRepository;
import com.whale.crm.ai.domain.AiChatDelegationRun;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.wecom.adapter.WecomExternalContactSenderRouter;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AiChatDelegationSettingsService {
    private static final String POLICY_SCOPE = "ai.chat-delegation.policy";
    private static final String EMERGENCY_SCOPE = "ai.chat-delegation.emergency";
    private static final String CUSTOMER_SCOPE = "ai.chat-delegation.customer";

    private final AiChatDelegationRepository repository;
    private final CustomerScopeSupport scopeSupport;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final WecomExternalContactSenderRouter sender;
    private final WecomOfficialProperties officialProperties;
    private final IntegrationRuntimeConfigService runtimeConfigService;
    private final AiChatSendFenceService sendFenceService;
    private final boolean workerEnabled;
    private final boolean eventConsumerEnabled;
    private final boolean officialEnabled;

    public AiChatDelegationSettingsService(AiChatDelegationRepository repository, CustomerScopeSupport scopeSupport,
                                           CustomerRepository customerRepository, UserRepository userRepository,
                                           AuditService auditService, IdempotencyService idempotencyService,
                                           ObjectMapper objectMapper, WecomExternalContactSenderRouter sender,
                                           WecomOfficialProperties officialProperties,
                                           IntegrationRuntimeConfigService runtimeConfigService,
                                           AiChatSendFenceService sendFenceService,
                                           @Value("${app.ai.delegation.worker.enabled:false}") boolean workerEnabled,
                                           @Value("${app.ai.delegation.kafka.enabled:false}") boolean eventConsumerEnabled,
                                           @Value("${app.wecom.external-contact.official-enabled:false}")
                                           boolean officialEnabled) {
        this.repository = repository;
        this.scopeSupport = scopeSupport;
        this.customerRepository = customerRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.sender = sender;
        this.officialProperties = officialProperties;
        this.runtimeConfigService = runtimeConfigService;
        this.sendFenceService = sendFenceService;
        this.workerEnabled = workerEnabled;
        this.eventConsumerEnabled = eventConsumerEnabled;
        this.officialEnabled = officialEnabled;
    }

    @Transactional(readOnly = true)
    public AiChatDelegationPolicyResponse getPolicy(long tenantId) {
        return AiChatDelegationPolicyResponse.of(loadPolicy(tenantId));
    }

    @Transactional
    public AiChatDelegationPolicyResponse updatePolicy(CurrentUser user, UpdateAiChatDelegationPolicyRequest request,
                                                       String idempotencyKey) {
        long tenantId = user.tenantId();
        String key = requireKey(idempotencyKey);
        String payload = canonical(request);
        if (idempotencyService.lookup(tenantId, POLICY_SCOPE, key, payload).isPresent()) {
            return getPolicy(tenantId);
        }
        AiChatDelegationPolicy before = loadPolicy(tenantId);
        requireVersion(before.version(), request.version());
        List<String> terms = normalizeTerms(request.blockedTerms());
        List<AiChatDelegationPolicy.ScopeOverride> scopes = normalizeScopes(tenantId, request.scopes());
        LocalTime start = LocalTime.parse(request.allowedStart());
        LocalTime end = LocalTime.parse(request.allowedEnd());
        if (start.equals(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "允许时段开始与结束不能相同");
        }
        try {
            ZoneId.of(request.zoneId());
        } catch (java.time.DateTimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "时区无效");
        }
        AiChatDelegationPolicy after = new AiChatDelegationPolicy(tenantId, request.enabled(),
                before.emergencyStopped(), request.minIntervalSeconds(), request.dailyLimit(), start, end,
                request.zoneId(), terms, before.version(), scopes);
        savePolicy(before, after, user.userId());
        repository.replaceScopes(tenantId, scopes);
        AiChatDelegationPolicy saved = loadPolicy(tenantId);
        auditService.record(tenantId, user.userId(), "AI_CHAT_DELEGATION_POLICY_UPDATE",
                "AI_CHAT_DELEGATION_POLICY", Long.toString(tenantId),
                AiChatDelegationPolicyResponse.of(before), AiChatDelegationPolicyResponse.of(saved), request.reason());
        idempotencyService.recordSuccess(tenantId, POLICY_SCOPE, key, "AI_CHAT_DELEGATION_POLICY",
                Long.toString(tenantId), payload);
        return AiChatDelegationPolicyResponse.of(saved);
    }

    @Transactional
    public AiChatDelegationPolicyResponse emergencyStop(CurrentUser user, AiChatDelegationEmergencyStopRequest request,
                                                        String idempotencyKey) {
        long tenantId = user.tenantId();
        String key = requireKey(idempotencyKey);
        String payload = canonical(request);
        if (idempotencyService.lookup(tenantId, EMERGENCY_SCOPE, key, payload).isPresent()) {
            return getPolicy(tenantId);
        }
        AiChatDelegationPolicy before = loadPolicy(tenantId);
        requireVersion(before.version(), request.version());
        AiChatDelegationPolicy after = new AiChatDelegationPolicy(tenantId, before.enabled(), request.stopped(),
                before.minIntervalSeconds(), before.dailyLimit(), before.allowedStart(), before.allowedEnd(),
                before.zoneId(), before.blockedTerms(), before.version(), before.scopes());
        savePolicy(before, after, user.userId());
        if (request.stopped()) {
            sendFenceService.blockTenant(tenantId);
        }
        AiChatDelegationPolicy saved = loadPolicy(tenantId);
        auditService.record(tenantId, user.userId(), request.stopped() ? "AI_CHAT_DELEGATION_EMERGENCY_STOP"
                        : "AI_CHAT_DELEGATION_EMERGENCY_RESUME", "AI_CHAT_DELEGATION_POLICY",
                Long.toString(tenantId), AiChatDelegationPolicyResponse.of(before),
                AiChatDelegationPolicyResponse.of(saved), request.reason());
        idempotencyService.recordSuccess(tenantId, EMERGENCY_SCOPE, key, "AI_CHAT_DELEGATION_POLICY",
                Long.toString(tenantId), payload);
        return AiChatDelegationPolicyResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public AiChatDelegationCustomerControlResponse getCustomerControl(CurrentUser user, long customerId) {
        scopeSupport.requireVisible(user.tenantId(), customerId, user);
        return AiChatDelegationCustomerControlResponse.of(loadControl(user.tenantId(), customerId));
    }

    @Transactional
    public AiChatDelegationCustomerControlResponse setCustomerControl(CurrentUser user, long customerId,
                                                                      AiChatDelegationCustomerControlRequest request,
                                                                      String idempotencyKey) {
        long tenantId = user.tenantId();
        scopeSupport.requireVisibleForUpdate(tenantId, customerId, user);
        String key = requireKey(idempotencyKey);
        String payload = customerId + "|" + canonical(request);
        if (idempotencyService.lookup(tenantId, CUSTOMER_SCOPE, key, payload).isPresent()) {
            return getCustomerControl(user, customerId);
        }
        AiChatDelegationCustomerControl before = loadControl(tenantId, customerId);
        requireVersion(before.version(), request.version());
        try {
            if (before.id() == null) {
                repository.insertCustomerControl(tenantId, customerId, request.mode(), request.reason().strip(),
                        user.userId());
            } else if (repository.updateCustomerControl(tenantId, customerId, request.mode(), request.reason().strip(),
                    user.userId(), request.version()) != 1) {
                throw conflict("客户托管状态已变化，请刷新后重试");
            }
        } catch (DuplicateKeyException ex) {
            throw conflict("客户托管状态已变化，请刷新后重试");
        }
        if (!"ACTIVE".equals(request.mode())) {
            java.util.List<AiChatDelegationRun> pending = repository.findPendingForCustomerForUpdate(tenantId, customerId);
            java.time.Instant now = java.time.Instant.now();
            for (AiChatDelegationRun run : pending) {
                repository.insertAttempt(new AiChatDelegationAttempt(null, tenantId, run.id(), "COMPLIANCE", "SKIPPED",
                        run.sendProvider(), run.providerMessageId(), request.mode(), now));
            }
            sendFenceService.blockCustomer(tenantId, customerId);
            repository.blockPendingForCustomer(tenantId, customerId, request.mode());
        }
        AiChatDelegationCustomerControl after = loadControl(tenantId, customerId);
        auditService.record(tenantId, user.userId(), "AI_CHAT_DELEGATION_CUSTOMER_" + request.mode(),
                "AI_CHAT_DELEGATION_CUSTOMER", Long.toString(customerId),
                AiChatDelegationCustomerControlResponse.of(before), AiChatDelegationCustomerControlResponse.of(after),
                request.reason());
        idempotencyService.recordSuccess(tenantId, CUSTOMER_SCOPE, key, "AI_CHAT_DELEGATION_CUSTOMER",
                Long.toString(customerId), payload);
        return AiChatDelegationCustomerControlResponse.of(after);
    }

    @Transactional(readOnly = true)
    public PageResponse<AiChatDelegationRunResponse> runs(CurrentUser user, Long customerId, String status,
                                                           int page, int size) {
        if (customerId != null) {
            scopeSupport.requireVisible(user.tenantId(), customerId, user);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scopeSupport.scope(user), user);
        List<AiChatDelegationRunResponse> items = repository.search(user.tenantId(), customerId, status,
                query.scopeType(), query.teamIds(), query.selfId(), (page - 1) * size, size)
                .stream().map(AiChatDelegationRunResponse::of).toList();
        return new PageResponse<>(items, repository.countSearch(user.tenantId(), customerId, status,
                query.scopeType(), query.teamIds(), query.selfId()), page, size);
    }

    @Transactional(readOnly = true)
    public AiChatDelegationStatusResponse status(CurrentUser user) {
        AiChatDelegationPolicy policy = loadPolicy(user.tenantId());
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scopeSupport.scope(user), user);
        Map<String, Long> counts = new LinkedHashMap<>();
        repository.countByStatus(user.tenantId(), query.scopeType(), query.teamIds(), query.selfId())
                .forEach(row -> counts.put(row.status(), row.count()));
        boolean realOfficial = "OFFICIAL".equals(sender.mode()) && officialEnabled && officialProperties.enabled()
                && runtimeConfigService.wecomOfficialEnabled(user.tenantId());
        return new AiChatDelegationStatusResponse(policy.enabled(), policy.emergencyStopped(), workerEnabled,
                eventConsumerEnabled, sender.mode(), realOfficial, counts);
    }

    private AiChatDelegationPolicy loadPolicy(long tenantId) {
        return repository.findPolicy(tenantId).orElseGet(() -> AiChatDelegationPolicy.defaults(tenantId));
    }

    private AiChatDelegationCustomerControl loadControl(long tenantId, long customerId) {
        return repository.findCustomerControl(tenantId, customerId)
                .orElseGet(() -> AiChatDelegationCustomerControl.active(tenantId, customerId));
    }

    private void savePolicy(AiChatDelegationPolicy before, AiChatDelegationPolicy after, long actorUserId) {
        try {
            if (repository.findPolicy(before.tenantId()).isEmpty()) {
                repository.insertPolicy(after, actorUserId);
            } else if (repository.updatePolicy(after, actorUserId, before.version()) != 1) {
                throw conflict("AI托管策略已变化，请刷新后重试");
            }
        } catch (DuplicateKeyException ex) {
            throw conflict("AI托管策略已变化，请刷新后重试");
        }
    }

    private List<String> normalizeTerms(List<String> input) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String term : input) {
            String normalized = term.strip();
            String key = normalized.toLowerCase(Locale.ROOT);
            if (values.putIfAbsent(key, normalized) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "禁发词不能重复");
            }
        }
        return values.values().stream().sorted().toList();
    }

    private List<AiChatDelegationPolicy.ScopeOverride> normalizeScopes(
            long tenantId, List<UpdateAiChatDelegationPolicyRequest.ScopeRequest> input) {
        Map<String, AiChatDelegationPolicy.ScopeOverride> values = new LinkedHashMap<>();
        for (UpdateAiChatDelegationPolicyRequest.ScopeRequest scope : input) {
            if ("TEAM".equals(scope.scopeType())) {
                if (!userRepository.teamExists(tenantId, scope.scopeId())) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "团队不存在或不属于当前租户");
                }
            } else {
                SysUser staff = userRepository.findById(tenantId, scope.scopeId())
                        .filter(user -> "EMPLOYEE".equals(user.getUserType())).orElse(null);
                if (staff == null || (scope.enabled() && !staff.isActive())) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "管家不存在、非员工或已停用");
                }
            }
            String key = scope.scopeType() + ":" + scope.scopeId();
            AiChatDelegationPolicy.ScopeOverride value = new AiChatDelegationPolicy.ScopeOverride(
                    scope.scopeType(), scope.scopeId(), scope.enabled());
            if (values.putIfAbsent(key, value) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同一团队或管家不能重复配置");
            }
        }
        return values.values().stream().sorted(Comparator.comparing(AiChatDelegationPolicy.ScopeOverride::scopeType)
                .thenComparingLong(AiChatDelegationPolicy.ScopeOverride::scopeId)).toList();
    }

    private void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw conflict("配置版本已变化，请刷新后重试");
        }
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供1至128字符的幂等键");
        }
        return key.strip();
    }

    private String canonical(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("AI托管请求规范化失败", ex);
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
