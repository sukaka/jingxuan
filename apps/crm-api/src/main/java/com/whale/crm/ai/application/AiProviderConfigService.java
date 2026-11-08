package com.whale.crm.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.AiProviderConfigRequest;
import com.whale.crm.ai.api.dto.AiProviderConfigResponse;
import com.whale.crm.ai.domain.AiProviderConfig;
import com.whale.crm.ai.domain.AiProviderConfigRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class AiProviderConfigService {
    private static final String SAVE_SCOPE = "ai.provider.config.save";
    private static final String TEST_SCOPE = "ai.provider.config.test";
    private static final String CONNECTION_ERROR = "AI 模型连接失败，请检查配置或网络";

    private final AiProviderConfigRepository repository;
    private final AesGcmCipher cipher;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final AiWorkerClient workerClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final boolean allowHttp;
    private final boolean environmentWorkerEnabled;

    public AiProviderConfigService(AiProviderConfigRepository repository, AesGcmCipher cipher,
                                   IdempotencyService idempotencyService, AuditService auditService,
                                   AiWorkerClient workerClient, ObjectMapper objectMapper,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${app.ai.provider.allow-http:false}") boolean allowHttp,
                                   @Value("${app.ai.worker.enabled:false}") boolean environmentWorkerEnabled) {
        this.repository = repository;
        this.cipher = cipher;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.workerClient = workerClient;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.allowHttp = allowHttp;
        this.environmentWorkerEnabled = environmentWorkerEnabled;
    }

    @Transactional(readOnly = true)
    public AiProviderConfigResponse get(long tenantId) {
        return repository.find(tenantId).map(this::view).orElseGet(this::emptyView);
    }

    public AiProviderConfigResponse save(CurrentUser user, AiProviderConfigRequest request, String key) {
        String payload = json(request);
        if (idempotencyService.lookup(user.tenantId(), SAVE_SCOPE, key, payload).isPresent()) {
            return get(user.tenantId());
        }
        validate(request);
        try {
            AiProviderConfig saved = transactions.execute(status -> {
                AiProviderConfig existing = repository.findForUpdate(user.tenantId()).orElse(null);
                if (existing == null && request.version() != 0) {
                    throw versionConflict();
                }
                if (existing != null && !request.version().equals(existing.version())) {
                    throw versionConflict();
                }
                String keyCipher = request.apiKey() == null || request.apiKey().isBlank()
                        ? existing == null ? null : existing.apiKeyCipher() : cipher.encrypt(request.apiKey().strip());
                requireComplete(request, keyCipher);
                AiProviderConfig changed = new AiProviderConfig(existing == null ? null : existing.id(), user.tenantId(),
                        request.provider(), normalize(request.baseUrl()), keyCipher, normalize(request.model()),
                        request.timeoutSeconds(), request.enabled(), "UNVERIFIED", null, null,
                        existing == null ? null : existing.createdAt(), null,
                        existing == null ? Long.valueOf(0L) : existing.version());
                if (existing == null) {
                    repository.insert(user.tenantId(), changed);
                } else if (repository.update(user.tenantId(), existing.id(), existing.version(), changed) != 1) {
                    throw versionConflict();
                }
                AiProviderConfig result = repository.find(user.tenantId()).orElseThrow();
                idempotencyService.recordSuccess(user.tenantId(), SAVE_SCOPE, key, "AI_PROVIDER_CONFIG",
                        result.id().toString(), payload);
                auditService.record(user.tenantId(), user.userId(), "AI_PROVIDER_CONFIG_SAVE", "AI_PROVIDER_CONFIG",
                        result.id().toString(), snapshot(existing), snapshot(result), null);
                return result;
            });
            return view(Objects.requireNonNull(saved));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 供应商配置已存在，请刷新后重试");
        }
    }

    public AiProviderConfigResponse testConnection(CurrentUser user, String key) {
        String payload = "{}";
        if (idempotencyService.lookup(user.tenantId(), TEST_SCOPE, key, payload).isPresent()) {
            return get(user.tenantId());
        }
        AiProviderConfig current = repository.find(user.tenantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "请先保存 AI 供应商配置"));
        if ("disabled".equals(current.provider())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "disabled 供应商无需连接测试");
        }
        String status = "CONNECTED";
        String error = null;
        Instant connectedAt = Instant.now();
        try {
            workerClient.testConnection(workerConfig(current));
        } catch (Exception ex) {
            status = "FAILED";
            error = CONNECTION_ERROR;
            connectedAt = null;
        }
        String finalStatus = status;
        String finalError = error;
        Instant finalConnectedAt = connectedAt;
        AiProviderConfig result = transactions.execute(transaction -> {
            if (repository.updateConnection(user.tenantId(), current.id(), current.version(), finalStatus,
                    finalError, finalConnectedAt) != 1) {
                throw versionConflict();
            }
            AiProviderConfig saved = repository.find(user.tenantId()).orElseThrow();
            idempotencyService.recordSuccess(user.tenantId(), TEST_SCOPE, key, "AI_PROVIDER_CONFIG",
                    saved.id().toString(), payload);
            auditService.record(user.tenantId(), user.userId(), "AI_PROVIDER_CONNECTION_TEST", "AI_PROVIDER_CONFIG",
                    saved.id().toString(), snapshot(current), snapshot(saved), null);
            return saved;
        });
        return view(Objects.requireNonNull(result));
    }

    @Transactional(readOnly = true)
    public AiWorkerClient.ProviderConfig workerConfig(long tenantId) {
        return repository.find(tenantId).map(this::workerConfig).orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean workerEnabled(long tenantId) {
        return repository.find(tenantId)
                .map(config -> Boolean.TRUE.equals(config.enabled()) && !"disabled".equals(config.provider()))
                .orElse(environmentWorkerEnabled);
    }

    private AiWorkerClient.ProviderConfig workerConfig(AiProviderConfig config) {
        return new AiWorkerClient.ProviderConfig(config.provider(), config.baseUrl(),
                config.apiKeyCipher() == null ? null : cipher.decrypt(config.apiKeyCipher()), config.model(),
                config.timeoutSeconds());
    }

    private void validate(AiProviderConfigRequest request) {
        if (request.enabled() && "disabled".equals(request.provider())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "disabled 供应商不能启用 AI 任务");
        }
        String baseUrl = normalize(request.baseUrl());
        if (baseUrl != null) {
            validateBaseUrl(baseUrl);
        }
    }

    private void requireComplete(AiProviderConfigRequest request, String keyCipher) {
        if (!"openai_compatible".equals(request.provider())) {
            return;
        }
        if (normalize(request.baseUrl()) == null || normalize(request.model()) == null || keyCipher == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "openai_compatible 必须完整配置 baseUrl、apiKey 和 model");
        }
    }

    private void validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "AI baseUrl 格式无效");
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return;
            }
            if (!"http".equalsIgnoreCase(uri.getScheme())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "AI baseUrl 仅支持 HTTPS");
            }
            if (!allowHttp) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "AI baseUrl 默认必须使用 HTTPS");
            }
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仅允许明确配置的内网 HTTP 地址");
            }
            for (InetAddress address : addresses) {
                if (!isInternal(address)) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仅允许明确配置的内网 HTTP 地址");
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "AI baseUrl 格式无效或内网地址不可解析");
        }
    }

    private boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
    }

    private AiProviderConfigResponse view(AiProviderConfig config) {
        return new AiProviderConfigResponse(config.provider(), config.baseUrl(),
                config.apiKeyCipher() != null && !config.apiKeyCipher().isBlank(), config.model(),
                config.timeoutSeconds(), Boolean.TRUE.equals(config.enabled()), config.connectionStatus(),
                config.lastErrorMasked(), config.lastConnectedAt(), config.version());
    }

    private AiProviderConfigResponse emptyView() {
        return new AiProviderConfigResponse("disabled", null, false, null, 30, false,
                "UNVERIFIED", null, null, 0L);
    }

    private Map<String, Object> snapshot(AiProviderConfig config) {
        if (config == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("provider", config.provider());
        value.put("baseUrl", config.baseUrl());
        value.put("apiKeyConfigured", config.apiKeyCipher() != null && !config.apiKeyCipher().isBlank());
        value.put("model", config.model());
        value.put("timeoutSeconds", config.timeoutSeconds());
        value.put("enabled", config.enabled());
        value.put("connectionStatus", config.connectionStatus());
        value.put("lastErrorMasked", config.lastErrorMasked());
        value.put("lastConnectedAt", config.lastConnectedAt());
        value.put("version", config.version());
        return value;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().replaceAll("/+$", "");
    }

    private BusinessException versionConflict() {
        return new BusinessException(ErrorCode.CONFLICT, "AI 供应商配置版本已变化，请刷新后重试");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("请求无法序列化", ex);
        }
    }
}
