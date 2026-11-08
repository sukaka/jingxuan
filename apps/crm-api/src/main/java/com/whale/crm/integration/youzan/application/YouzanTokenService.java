package com.whale.crm.integration.youzan.application;

import com.whale.crm.integration.youzan.adapter.YouzanRemoteClient;
import com.whale.crm.integration.youzan.adapter.YouzanTokenResponse;
import com.whale.crm.integration.youzan.adapter.YouzanTime;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class YouzanTokenService {
    private final YouzanConfigRepository repository;
    private final YouzanRemoteClient remoteClient;
    private final AesGcmCipher cipher;
    private final TransactionTemplate transactions;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public YouzanTokenService(YouzanConfigRepository repository, YouzanRemoteClient remoteClient,
                              AesGcmCipher cipher, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.remoteClient = remoteClient;
        this.cipher = cipher;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public String accessToken(long tenantId, String shopId) {
        YouzanShopConfig config = config(tenantId, shopId);
        if (usable(config)) {
            return cipher.decrypt(config.accessTokenCipher());
        }
        Object lock = locks.computeIfAbsent(tenantId + ":" + shopId, key -> new Object());
        synchronized (lock) {
            try {
                return transactions.execute(status -> refreshLocked(tenantId, shopId, false));
            } catch (BusinessException ex) {
                markFailed(tenantId, shopId, ex);
                throw ex;
            }
        }
    }

    public String refreshAccessToken(long tenantId, String shopId) {
        Object lock = locks.computeIfAbsent(tenantId + ":" + shopId, key -> new Object());
        synchronized (lock) {
            try {
                return transactions.execute(status -> refreshLocked(tenantId, shopId, true));
            } catch (BusinessException ex) {
                markFailed(tenantId, shopId, ex);
                throw ex;
            }
        }
    }

    public YouzanShopConfig testConnection(long tenantId, String shopId) {
        try {
            String token = accessToken(tenantId, shopId);
            Instant end = Instant.now();
            Map<String, Object> query = Map.of("page_no", 1, "page_size", 1,
                    "start_created", YouzanTime.format(end.minus(1, ChronoUnit.DAYS)), "end_created", YouzanTime.format(end));
            remoteClient.callApi(token, "youzan.trades.sold.get", "4.0.2", query);
            return transactions.execute(status -> {
                YouzanShopConfig config = repository.findShopConfigForUpdate(tenantId, shopId).orElseThrow();
                repository.updateShopConnectionStatus(tenantId, config.id(), config.version(), "CONNECTED", null, Instant.now());
                return repository.findShopConfig(tenantId, shopId).orElseThrow();
            });
        } catch (BusinessException ex) {
            markFailed(tenantId, shopId, ex);
            throw ex;
        }
    }

    private String refreshLocked(long tenantId, String shopId, boolean force) {
        YouzanShopConfig config = repository.findShopConfigForUpdate(tenantId, shopId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "有赞店铺配置不存在"));
        if (!force && usable(config)) {
            return cipher.decrypt(config.accessTokenCipher());
        }
        YouzanTokenResponse token = remoteClient.fetchSelfToken(config.clientId(),
                cipher.decrypt(config.clientSecretCipher()), shopId);
        YouzanShopConfig updated = new YouzanShopConfig(config.id(), config.tenantId(), config.shopId(),
                config.shopName(), config.clientId(), config.clientSecretCipher(), cipher.encrypt(token.accessToken()),
                cipher.encrypt(token.refreshToken()), token.expiresAt(), "CONNECTED", config.syncEnabled(),
                null, Instant.now(), config.createdAt(), config.version());
        if (repository.updateShopTokens(tenantId, config.id(), config.version(), updated) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "有赞店铺Token已更新，请重试");
        }
        return token.accessToken();
    }

    private void markFailed(long tenantId, String shopId, BusinessException error) {
        transactions.executeWithoutResult(status -> repository.findShopConfigForUpdate(tenantId, shopId)
                .ifPresent(config -> repository.updateShopConnectionStatus(tenantId, config.id(), config.version(),
                        "FAILED", safeError(error.getMessage()), null)));
    }

    private YouzanShopConfig config(long tenantId, String shopId) {
        return repository.findShopConfig(tenantId, shopId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "有赞店铺配置不存在"));
    }

    private boolean usable(YouzanShopConfig config) {
        return config.accessTokenCipher() != null && config.tokenExpiresAt() != null
                && config.tokenExpiresAt().isAfter(Instant.now().plus(5, ChronoUnit.MINUTES));
    }

    private static String safeError(String error) {
        if (error == null) {
            return "有赞连接失败";
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
