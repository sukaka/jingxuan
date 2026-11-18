package com.whale.crm.followup.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.followup.api.dto.FollowupPolicyResponse;
import com.whale.crm.followup.api.dto.UpdateFollowupPolicyRequest;
import com.whale.crm.followup.domain.FollowupAutomationRepository;
import com.whale.crm.followup.domain.FollowupPolicy;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FollowupPolicyService {
    private static final String IDEMPOTENCY_SCOPE = "followup.policy.update";

    private final FollowupAutomationRepository repository;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public FollowupPolicyService(FollowupAutomationRepository repository, AuditService auditService,
                                 IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public FollowupPolicyResponse get(long tenantId) {
        return toResponse(load(tenantId));
    }

    @Transactional(readOnly = true)
    public Instant nextFollowupAt(long tenantId, String intentLevel, Instant contactAt, Instant explicitNextAt) {
        if (explicitNextAt != null) {
            return explicitNextAt;
        }
        if (contactAt == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "沟通时间不能为空");
        }
        return contactAt.plus(load(tenantId).intervalFor(intentLevel), ChronoUnit.DAYS);
    }

    @Transactional
    public FollowupPolicyResponse update(long tenantId, CurrentUser user,
                                         UpdateFollowupPolicyRequest request, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        String payload = canonical(request);
        if (idempotencyService.lookup(tenantId, IDEMPOTENCY_SCOPE, key, payload).isPresent()) {
            return get(tenantId);
        }
        FollowupPolicy before = load(tenantId);
        boolean exists = repository.findPolicy(tenantId).isPresent();
        if (before.version() != request.version()) {
            throw new BusinessException(ErrorCode.CONFLICT, "跟进策略已被他人修改，请刷新后重试");
        }
        List<FollowupPolicy.IntentOverride> overrides = normalize(request.intentOverrides());
        if (exists) {
            if (repository.updatePolicy(tenantId, request.defaultIntervalDays(), user.userId(),
                    request.version()) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "跟进策略已被他人修改，请刷新后重试");
            }
        } else {
            repository.insertPolicy(tenantId, request.defaultIntervalDays(), user.userId());
        }
        repository.replaceIntentOverrides(tenantId, overrides);
        FollowupPolicy after = load(tenantId);
        auditService.record(tenantId, user.userId(), "FOLLOWUP_POLICY_UPDATE", "FOLLOWUP_POLICY",
                Long.toString(tenantId), snapshot(before), snapshot(after), "仅作用于新创建跟进");
        idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE, key, "FOLLOWUP_POLICY",
                Long.toString(tenantId), payload);
        return toResponse(after);
    }

    private FollowupPolicy load(long tenantId) {
        return repository.findPolicy(tenantId)
                .map(row -> new FollowupPolicy(row.tenantId(), row.defaultIntervalDays(), row.version(),
                        repository.findIntentOverrides(tenantId)))
                .orElseGet(() -> FollowupPolicy.defaults(tenantId));
    }

    private List<FollowupPolicy.IntentOverride> normalize(
            List<UpdateFollowupPolicyRequest.IntentOverrideRequest> input) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (UpdateFollowupPolicyRequest.IntentOverrideRequest override : input) {
            String code = override.intentLevel().strip().toUpperCase(Locale.ROOT);
            if (values.putIfAbsent(code, override.intervalDays()) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同一意向级别只能配置一个间隔");
            }
        }
        return values.entrySet().stream()
                .map(entry -> new FollowupPolicy.IntentOverride(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparing(FollowupPolicy.IntentOverride::intentLevel)).toList();
    }

    private FollowupPolicyResponse toResponse(FollowupPolicy policy) {
        return new FollowupPolicyResponse(policy.tenantId(), policy.defaultIntervalDays(), policy.version(),
                policy.intentOverrides().stream()
                        .map(value -> new FollowupPolicyResponse.IntentOverrideResponse(
                                value.intentLevel(), value.intervalDays())).toList());
    }

    private Map<String, Object> snapshot(FollowupPolicy policy) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("defaultIntervalDays", policy.defaultIntervalDays());
        value.put("intentOverrides", policy.intentOverrides());
        value.put("version", policy.version());
        return value;
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
            throw new IllegalStateException("跟进策略请求规范化失败", ex);
        }
    }
}
