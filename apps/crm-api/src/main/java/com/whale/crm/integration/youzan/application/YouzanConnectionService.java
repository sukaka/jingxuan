package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.api.dto.YouzanShopConfigRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanShopConfigView;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncPolicyRequest;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.integration.youzan.domain.YouzanSyncJob;
import com.whale.crm.integration.youzan.domain.YouzanSyncPolicy;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

@Service
public class YouzanConnectionService {
    private final YouzanConfigRepository repository;
    private final YouzanTokenService tokenService;
    private final AesGcmCipher cipher;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final IntegrationRuntimeConfigService runtimeConfig;

    public YouzanConnectionService(YouzanConfigRepository repository, YouzanTokenService tokenService,
                                   AesGcmCipher cipher, IdempotencyService idempotencyService,
                                   AuditService auditService, ObjectMapper objectMapper,
                                   PlatformTransactionManager transactionManager,
                                   IntegrationRuntimeConfigService runtimeConfig) {
        this.repository = repository;
        this.tokenService = tokenService;
        this.cipher = cipher;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.runtimeConfig = runtimeConfig;
    }

    public List<YouzanShopConfigView> shops(long tenantId) {
        return repository.findShopConfigs(tenantId).stream().map(this::view).toList();
    }

    public YouzanShopConfigView saveShop(CurrentUser user, YouzanShopConfigRequest request, String key) {
        String payload = json(request);
        String scope = "youzan.shop.config";
        IdempotencyRecord replay = idempotencyService.lookup(user.tenantId(), scope, key, payload).orElse(null);
        if (replay != null) {
            return repository.findShopConfig(user.tenantId(), request.shopId()).map(this::view).orElseThrow();
        }
        try {
            YouzanShopConfig result = transactions.execute(status -> {
                YouzanShopConfig existing = repository.findShopConfigForUpdate(user.tenantId(), request.shopId()).orElse(null);
                if (existing == null && (request.clientSecret() == null || request.clientSecret().isBlank())) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "新店铺必须配置clientSecret");
                }
                String secret = request.clientSecret() == null || request.clientSecret().isBlank()
                        ? existing.clientSecretCipher() : cipher.encrypt(request.clientSecret());
                if (existing == null) {
                    repository.insertShopConfig(user.tenantId(), new YouzanShopConfig(null, user.tenantId(),
                            request.shopId(), request.shopName(), request.clientId(), secret, null, null, null,
                            "UNVERIFIED", request.syncEnabled(), null, null, null, null));
                } else {
                    if (request.version() == null || !request.version().equals(existing.version())) {
                        throw new BusinessException(ErrorCode.CONFLICT, "店铺配置版本已变化");
                    }
                    YouzanShopConfig changed = new YouzanShopConfig(existing.id(), user.tenantId(), request.shopId(),
                            request.shopName(), request.clientId(), secret, existing.accessTokenCipher(),
                            existing.refreshTokenCipher(), existing.tokenExpiresAt(), existing.connectionStatus(),
                            request.syncEnabled(), existing.lastError(), existing.lastConnectedAt(), existing.createdAt(),
                            existing.version());
                    if (repository.updateShopConfig(user.tenantId(), existing.id(), existing.version(), changed) != 1) {
                        throw new BusinessException(ErrorCode.CONFLICT, "店铺配置版本冲突");
                    }
                }
                YouzanShopConfig saved = repository.findShopConfig(user.tenantId(), request.shopId()).orElseThrow();
                idempotencyService.recordSuccess(user.tenantId(), scope, key, "YOUZAN_SHOP",
                        saved.id().toString(), payload);
                auditService.record(user.tenantId(), user.userId(), "YOUZAN_SHOP_CONFIG_SAVE", "YOUZAN_SHOP",
                        saved.id().toString(), existing == null ? null : view(existing), view(saved), null);
                return saved;
            });
            return view(Objects.requireNonNull(result));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "店铺或应用授权配置已存在");
        }
    }

    public YouzanShopConfigView testConnection(CurrentUser user, String shopId) {
        if (!runtimeConfig.youzanRealEnabled(user.tenantId())) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "真实有赞功能开关未启用，仅允许保存配置");
        }
        YouzanShopConfig result = tokenService.testConnection(user.tenantId(), shopId);
        auditService.record(user.tenantId(), user.userId(), "YOUZAN_CONNECTION_TEST", "YOUZAN_SHOP",
                result.id().toString(), null, view(result), null);
        return view(result);
    }

    public YouzanSyncPolicy policy(long tenantId, String shopId) {
        requireShop(tenantId, shopId);
        return repository.findSyncPolicy(tenantId, shopId).orElse(null);
    }

    public YouzanSyncPolicy savePolicy(CurrentUser user, String shopId, YouzanSyncPolicyRequest request) {
        return transactions.execute(status -> {
            requireShop(user.tenantId(), shopId);
            YouzanSyncPolicy existing = repository.findSyncPolicy(user.tenantId(), shopId).orElse(null);
            if (existing == null) {
                repository.insertSyncPolicy(user.tenantId(), new YouzanSyncPolicy(null, shopId,
                        request.initialStartTime(), request.intervalSeconds(), request.overlapSeconds(),
                        request.pageSize(), request.enabled(), request.nextRunAt(), null, null));
            } else {
                if (request.version() == null || !request.version().equals(existing.version())) {
                    throw new BusinessException(ErrorCode.CONFLICT, "同步策略版本已变化");
                }
                YouzanSyncPolicy changed = new YouzanSyncPolicy(existing.id(), shopId, request.initialStartTime(),
                        request.intervalSeconds(), request.overlapSeconds(), request.pageSize(), request.enabled(),
                        request.nextRunAt() == null ? existing.nextRunAt() : request.nextRunAt(),
                        existing.lastScheduledAt(), existing.version());
                if (repository.updateSyncPolicy(user.tenantId(), existing.id(), existing.version(), changed) != 1) {
                    throw new BusinessException(ErrorCode.CONFLICT, "同步策略版本冲突");
                }
            }
            YouzanSyncPolicy saved = repository.findSyncPolicy(user.tenantId(), shopId).orElseThrow();
            auditService.record(user.tenantId(), user.userId(), "YOUZAN_SYNC_POLICY_SAVE", "YOUZAN_SHOP",
                    shopId, existing, saved, null);
            return saved;
        });
    }

    public PageResponse<YouzanSyncJob> jobs(long tenantId, String shopId, int page, int size) {
        requireShop(tenantId, shopId);
        return new PageResponse<>(repository.findSyncJobs(tenantId, shopId, (page - 1) * size, size),
                repository.countSyncJobs(tenantId, shopId), page, size);
    }

    private void requireShop(long tenantId, String shopId) {
        if (repository.findShopConfig(tenantId, shopId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "有赞店铺配置不存在");
        }
    }

    private YouzanShopConfigView view(YouzanShopConfig config) {
        return new YouzanShopConfigView(config.id(), config.shopId(), config.shopName(), config.clientId(),
                config.clientSecretCipher() != null && !config.clientSecretCipher().isBlank(), config.tokenExpiresAt(),
                config.connectionStatus(), Boolean.TRUE.equals(config.syncEnabled()), config.lastError(),
                config.lastConnectedAt(), "/api/v1/webhooks/youzan/official", config.version());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("请求无法序列化", ex);
        }
    }
}
