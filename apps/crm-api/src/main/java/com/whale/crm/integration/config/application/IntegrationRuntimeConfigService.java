package com.whale.crm.integration.config.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.config.api.dto.IntegrationRuntimeConfigRequest;
import com.whale.crm.integration.config.api.dto.IntegrationRuntimeConfigResponse;
import com.whale.crm.integration.config.domain.IntegrationRuntimeConfig;
import com.whale.crm.integration.config.domain.IntegrationRuntimeConfigRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class IntegrationRuntimeConfigService {
    private static final String SAVE_SCOPE = "integration.runtime.config.save";

    private final IntegrationRuntimeConfigRepository repository;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final boolean defaultYouzanRealEnabled;
    private final boolean defaultWecomArchiveRealEnabled;
    private final boolean defaultWecomOfficialEnabled;

    public IntegrationRuntimeConfigService(IntegrationRuntimeConfigRepository repository,
                                           IdempotencyService idempotencyService, AuditService auditService,
                                           ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
                                           @Value("${FEATURE_REAL_YOUZAN:false}") boolean defaultYouzanRealEnabled,
                                           @Value("${FEATURE_REAL_WECOM:false}") boolean defaultWecomArchiveRealEnabled,
                                           @Value("${app.wecom.official.enabled:false}") boolean defaultWecomOfficialEnabled) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.defaultYouzanRealEnabled = defaultYouzanRealEnabled;
        this.defaultWecomArchiveRealEnabled = defaultWecomArchiveRealEnabled;
        this.defaultWecomOfficialEnabled = defaultWecomOfficialEnabled;
    }

    @Transactional(readOnly = true)
    public IntegrationRuntimeConfigResponse get(long tenantId) {
        return repository.find(tenantId).map(this::view).orElseGet(this::environmentView);
    }

    @Transactional(readOnly = true)
    public boolean youzanRealEnabled(long tenantId) {
        return repository.find(tenantId).map(IntegrationRuntimeConfig::youzanRealEnabled)
                .orElse(defaultYouzanRealEnabled);
    }

    @Transactional(readOnly = true)
    public boolean wecomArchiveRealEnabled(long tenantId) {
        return repository.find(tenantId).map(IntegrationRuntimeConfig::wecomArchiveRealEnabled)
                .orElse(defaultWecomArchiveRealEnabled);
    }

    @Transactional(readOnly = true)
    public boolean wecomOfficialEnabled(long tenantId) {
        return repository.find(tenantId).map(IntegrationRuntimeConfig::wecomOfficialEnabled)
                .orElse(defaultWecomOfficialEnabled);
    }

    public IntegrationRuntimeConfigResponse save(CurrentUser user, IntegrationRuntimeConfigRequest request, String key) {
        String payload = json(request);
        if (idempotencyService.lookup(user.tenantId(), SAVE_SCOPE, key, payload).isPresent()) {
            return get(user.tenantId());
        }
        try {
            IntegrationRuntimeConfig saved = transactions.execute(status -> {
                IntegrationRuntimeConfig existing = repository.findForUpdate(user.tenantId()).orElse(null);
                if (existing == null && request.version() != 0) {
                    throw versionConflict();
                }
                if (existing != null && !request.version().equals(existing.version())) {
                    throw versionConflict();
                }
                IntegrationRuntimeConfig changed = new IntegrationRuntimeConfig(
                        existing == null ? null : existing.id(), user.tenantId(),
                        request.youzanRealEnabled(), request.wecomArchiveRealEnabled(), request.wecomOfficialEnabled(),
                        existing == null ? 0 : existing.version());
                if (existing == null) {
                    repository.insert(user.tenantId(), changed);
                } else if (repository.update(user.tenantId(), existing.id(), existing.version(), changed) != 1) {
                    throw versionConflict();
                }
                IntegrationRuntimeConfig result = repository.find(user.tenantId()).orElseThrow();
                idempotencyService.recordSuccess(user.tenantId(), SAVE_SCOPE, key, "INTEGRATION_RUNTIME_CONFIG",
                        Long.toString(user.tenantId()), payload);
                auditService.record(user.tenantId(), user.userId(), "INTEGRATION_RUNTIME_CONFIG_SAVE",
                        "INTEGRATION_RUNTIME_CONFIG", Long.toString(user.tenantId()),
                        snapshot(existing), snapshot(result), null);
                return result;
            });
            return view(Objects.requireNonNull(saved));
        } catch (DuplicateKeyException ex) {
            throw versionConflict();
        }
    }

    private IntegrationRuntimeConfigResponse view(IntegrationRuntimeConfig config) {
        return new IntegrationRuntimeConfigResponse(config.youzanRealEnabled(), config.wecomArchiveRealEnabled(),
                config.wecomOfficialEnabled(), config.version(), "DATABASE");
    }

    private IntegrationRuntimeConfigResponse environmentView() {
        return new IntegrationRuntimeConfigResponse(defaultYouzanRealEnabled, defaultWecomArchiveRealEnabled,
                defaultWecomOfficialEnabled, 0, "ENVIRONMENT");
    }

    private Map<String, Object> snapshot(IntegrationRuntimeConfig config) {
        if (config == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("youzanRealEnabled", config.youzanRealEnabled());
        value.put("wecomArchiveRealEnabled", config.wecomArchiveRealEnabled());
        value.put("wecomOfficialEnabled", config.wecomOfficialEnabled());
        value.put("version", config.version());
        return value;
    }

    private BusinessException versionConflict() {
        return new BusinessException(ErrorCode.CONFLICT, "集成开关已变化，请刷新后重试");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("请求无法序列化", ex);
        }
    }
}
