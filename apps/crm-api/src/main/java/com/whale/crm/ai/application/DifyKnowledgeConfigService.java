package com.whale.crm.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.DifyKnowledgeConfigRequest;
import com.whale.crm.ai.api.dto.DifyKnowledgeConfigResponse;
import com.whale.crm.ai.domain.DifyKnowledgeConfig;
import com.whale.crm.ai.domain.DifyKnowledgeConfigRepository;
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
public class DifyKnowledgeConfigService {
    private static final String SAVE_SCOPE = "ai.knowledge.config.save";
    private static final String TEST_SCOPE = "ai.knowledge.config.test";
    private static final String CONNECTION_ERROR = "Dify 知识服务连接失败，请检查配置或网络";

    private final DifyKnowledgeConfigRepository repository;
    private final AesGcmCipher cipher;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final KnowledgeWorkerClient workerClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final boolean allowHttp;

    public DifyKnowledgeConfigService(DifyKnowledgeConfigRepository repository, AesGcmCipher cipher,
                                      IdempotencyService idempotencyService, AuditService auditService,
                                      KnowledgeWorkerClient workerClient, ObjectMapper objectMapper,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${app.ai.knowledge.allow-http:false}") boolean allowHttp) {
        this.repository = repository;
        this.cipher = cipher;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.workerClient = workerClient;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.allowHttp = allowHttp;
    }

    @Transactional(readOnly = true)
    public DifyKnowledgeConfigResponse get(long tenantId) {
        return repository.find(tenantId).map(this::view).orElseGet(this::emptyView);
    }

    public DifyKnowledgeConfigResponse save(CurrentUser user, DifyKnowledgeConfigRequest request, String key) {
        String payload = json(request);
        if (idempotencyService.lookup(user.tenantId(), SAVE_SCOPE, key, payload).isPresent()) {
            return get(user.tenantId());
        }
        validate(request);
        try {
            DifyKnowledgeConfig saved = transactions.execute(status -> {
                DifyKnowledgeConfig existing = repository.findForUpdate(user.tenantId()).orElse(null);
                if (existing == null && request.version() != 0) {
                    throw versionConflict();
                }
                if (existing != null && !request.version().equals(existing.version())) {
                    throw versionConflict();
                }
                String keyCipher = request.apiKey() == null || request.apiKey().isBlank()
                        ? existing == null ? null : existing.appApiKeyCipher() : cipher.encrypt(request.apiKey().strip());
                String datasetKeyCipher = request.datasetApiKey() == null || request.datasetApiKey().isBlank()
                        ? existing == null ? null : existing.datasetApiKeyCipher()
                        : cipher.encrypt(request.datasetApiKey().strip());
                boolean syncEnabled = Boolean.TRUE.equals(request.syncEnabled());
                requireComplete(request, keyCipher, datasetKeyCipher, syncEnabled);
                DifyKnowledgeConfig changed = new DifyKnowledgeConfig(existing == null ? null : existing.id(), user.tenantId(),
                        request.provider(), normalize(request.baseUrl()), keyCipher, normalize(request.datasetId()),
                        datasetKeyCipher, syncEnabled, request.timeoutSeconds(), request.enabled(),
                        "UNVERIFIED", null, null, existing == null ? null : existing.createdAt(), null,
                        existing == null ? Long.valueOf(0L) : existing.version());
                if (existing == null) {
                    repository.insert(user.tenantId(), changed);
                } else if (repository.update(user.tenantId(), existing.id(), existing.version(), changed) != 1) {
                    throw versionConflict();
                }
                DifyKnowledgeConfig result = repository.find(user.tenantId()).orElseThrow();
                idempotencyService.recordSuccess(user.tenantId(), SAVE_SCOPE, key, "DIFY_KNOWLEDGE_CONFIG",
                        result.id().toString(), payload);
                auditService.record(user.tenantId(), user.userId(), "DIFY_KNOWLEDGE_CONFIG_SAVE", "DIFY_KNOWLEDGE_CONFIG",
                        result.id().toString(), snapshot(existing), snapshot(result), null);
                return result;
            });
            return view(Objects.requireNonNull(saved));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "Dify 知识配置已存在，请刷新后重试");
        }
    }

    public DifyKnowledgeConfigResponse testConnection(CurrentUser user, String key, String requestId) {
        String payload = "{}";
        if (idempotencyService.lookup(user.tenantId(), TEST_SCOPE, key, payload).isPresent()) {
            return get(user.tenantId());
        }
        DifyKnowledgeConfig current = repository.find(user.tenantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "请先保存 Dify 知识配置"));
        if ("disabled".equals(current.provider())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "disabled 知识服务无需连接测试");
        }
        String status = "CONNECTED";
        String error = null;
        Instant connectedAt = Instant.now();
        try {
            workerClient.testConnection(workerConfig(current), user.tenantId(), requestId);
        } catch (Exception ex) {
            status = "FAILED";
            error = CONNECTION_ERROR;
            connectedAt = null;
        }
        String finalStatus = status;
        String finalError = error;
        Instant finalConnectedAt = connectedAt;
        DifyKnowledgeConfig result = transactions.execute(transaction -> {
            if (repository.updateConnection(user.tenantId(), current.id(), current.version(), finalStatus,
                    finalError, finalConnectedAt) != 1) {
                throw versionConflict();
            }
            DifyKnowledgeConfig saved = repository.find(user.tenantId()).orElseThrow();
            idempotencyService.recordSuccess(user.tenantId(), TEST_SCOPE, key, "DIFY_KNOWLEDGE_CONFIG",
                    saved.id().toString(), payload);
            auditService.record(user.tenantId(), user.userId(), "DIFY_KNOWLEDGE_CONFIG_TEST", "DIFY_KNOWLEDGE_CONFIG",
                    saved.id().toString(), snapshot(current), snapshot(saved), null);
            return saved;
        });
        return view(Objects.requireNonNull(result));
    }

    @Transactional(readOnly = true)
    public boolean syncEnabled(long tenantId) {
        return repository.find(tenantId)
                .map(config -> Boolean.TRUE.equals(config.syncEnabled()) && !"disabled".equals(config.provider()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public String datasetId(long tenantId) {
        return repository.find(tenantId).map(DifyKnowledgeConfig::datasetId).orElse(null);
    }

    @Transactional(readOnly = true)
    public KnowledgeWorkerClient.DatasetProviderConfig datasetWorkerConfig(long tenantId) {
        return repository.find(tenantId)
                .filter(config -> Boolean.TRUE.equals(config.syncEnabled()) && !"disabled".equals(config.provider()))
                .map(config -> new KnowledgeWorkerClient.DatasetProviderConfig(config.provider(), config.baseUrl(),
                        config.datasetApiKeyCipher() == null ? null : cipher.decrypt(config.datasetApiKeyCipher()),
                        config.datasetId(), config.timeoutSeconds()))
                .orElse(null);
    }

    private KnowledgeWorkerClient.ProviderConfig workerConfig(DifyKnowledgeConfig config) {
        return new KnowledgeWorkerClient.ProviderConfig(config.provider(), config.baseUrl(),
                config.appApiKeyCipher() == null ? null : cipher.decrypt(config.appApiKeyCipher()), config.timeoutSeconds());
    }

    private void validate(DifyKnowledgeConfigRequest request) {
        if (request.enabled() && "disabled".equals(request.provider())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "disabled 知识服务不能同时启用");
        }
        if (Boolean.TRUE.equals(request.syncEnabled()) && "disabled".equals(request.provider())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "disabled 知识服务不能同步资料");
        }
        String baseUrl = normalize(request.baseUrl());
        if (baseUrl != null) {
            validateBaseUrl(baseUrl);
        }
    }

    private void requireComplete(DifyKnowledgeConfigRequest request, String keyCipher, String datasetKeyCipher,
                                 boolean syncEnabled) {
        if ("dify".equals(request.provider()) && (normalize(request.baseUrl()) == null || keyCipher == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "dify 必须完整配置 baseUrl 和 API Key");
        }
        if ("dify".equals(request.provider()) && syncEnabled
                && (normalize(request.datasetId()) == null || datasetKeyCipher == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "启用同步必须完整配置 Dataset ID 和 Dataset API Key");
        }
    }

    private void validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Dify baseUrl 格式无效");
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return;
            }
            if (!"http".equalsIgnoreCase(uri.getScheme())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Dify baseUrl 仅支持 HTTPS");
            }
            if (!allowHttp) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Dify baseUrl 默认必须使用 HTTPS");
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
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Dify baseUrl 格式无效或内网地址不可解析");
        }
    }

    private boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
    }

    private DifyKnowledgeConfigResponse view(DifyKnowledgeConfig config) {
        return new DifyKnowledgeConfigResponse(config.provider(), config.baseUrl(),
                config.appApiKeyCipher() != null && !config.appApiKeyCipher().isBlank(), config.datasetId(),
                config.datasetApiKeyCipher() != null && !config.datasetApiKeyCipher().isBlank(),
                Boolean.TRUE.equals(config.syncEnabled()), config.timeoutSeconds(),
                Boolean.TRUE.equals(config.enabled()), config.connectionStatus(), config.lastErrorMasked(),
                config.lastConnectedAt(), config.version());
    }

    private DifyKnowledgeConfigResponse emptyView() {
        return new DifyKnowledgeConfigResponse("disabled", null, false, null, false, false, 30, false,
                "UNVERIFIED", null, null, 0L);
    }

    private Map<String, Object> snapshot(DifyKnowledgeConfig config) {
        if (config == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("provider", config.provider());
        value.put("baseUrl", config.baseUrl());
        value.put("apiKeyConfigured", config.appApiKeyCipher() != null && !config.appApiKeyCipher().isBlank());
        value.put("datasetId", config.datasetId());
        value.put("datasetApiKeyConfigured", config.datasetApiKeyCipher() != null && !config.datasetApiKeyCipher().isBlank());
        value.put("syncEnabled", config.syncEnabled());
        value.put("timeoutSeconds", config.timeoutSeconds());
        value.put("enabled", config.enabled());
        value.put("connectionStatus", config.connectionStatus());
        value.put("lastErrorMasked", config.lastErrorMasked());
        value.put("lastConnectedAt", config.lastConnectedAt());
        value.put("version", config.version());
        return value;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip().replaceAll("/+$", "");
    }

    private BusinessException versionConflict() {
        return new BusinessException(ErrorCode.CONFLICT, "Dify 知识配置版本已变化，请刷新后重试");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("请求无法序列化", ex);
        }
    }
}
