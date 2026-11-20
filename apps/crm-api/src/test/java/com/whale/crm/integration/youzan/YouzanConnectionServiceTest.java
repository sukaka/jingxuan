package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.api.dto.YouzanShopConfigRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncPolicyRequest;
import com.whale.crm.integration.youzan.application.YouzanConnectionService;
import com.whale.crm.integration.youzan.application.YouzanTokenService;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouzanConnectionServiceTest {
    @Test
    void shopViewContainsOnlySecretStatusAndNeverCipherValues() {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        YouzanShopConfig config = new YouzanShopConfig(1L, 2L, "123", "虚构店铺", "client",
                "secret-cipher-value", "access-token-cipher-value", "refresh-cipher-value", null,
                "UNVERIFIED", false, null, null, null, 0L);
        when(repository.findShopConfigs(2L)).thenReturn(List.of(config));
        YouzanConnectionService service = service(repository, mock(AesGcmCipher.class), mock(IdempotencyService.class));

        String json = new ObjectMapper().valueToTree(service.shops(2L)).toString();

        assertThat(json).contains("secretConfigured").contains("client");
        assertThat(json).doesNotContain("secret-cipher-value", "access-token-cipher-value", "refresh-cipher-value");
    }

    @Test
    void updatingShopNameAndSyncEnabledWithoutCredentialChangePreservesTokensAndConnectedStatus() {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        Instant expiresAt = Instant.parse("2026-12-01T00:00:00Z");
        Instant connectedAt = Instant.parse("2026-11-01T08:00:00Z");
        YouzanShopConfig existing = new YouzanShopConfig(10L, 2L, "123", "旧店名", "client-1",
                "secret-cipher", "access-cipher", "refresh-cipher", expiresAt,
                "CONNECTED", false, null, connectedAt, Instant.parse("2026-10-01T00:00:00Z"), 3L);
        when(idempotency.lookup(eq(2L), eq("youzan.shop.config"), eq("key-1"), anyString())).thenReturn(Optional.empty());
        when(repository.findShopConfigForUpdate(2L, "123")).thenReturn(Optional.of(existing));
        when(repository.updateShopConfig(eq(2L), eq(10L), eq(3L), any())).thenReturn(1);
        when(repository.findShopConfig(2L, "123")).thenReturn(Optional.of(new YouzanShopConfig(10L, 2L, "123", "新店名",
                "client-1", "secret-cipher", "access-cipher", "refresh-cipher", expiresAt,
                "CONNECTED", true, null, connectedAt, existing.createdAt(), 4L)));
        YouzanConnectionService service = service(repository, cipher, idempotency);

        service.saveShop(admin(), new YouzanShopConfigRequest(" 123 ", " 新店名 ", " client-1 ", "   ", true, 3L), "key-1");

        ArgumentCaptor<YouzanShopConfig> captor = ArgumentCaptor.forClass(YouzanShopConfig.class);
        verify(repository).updateShopConfig(eq(2L), eq(10L), eq(3L), captor.capture());
        YouzanShopConfig updated = captor.getValue();
        assertThat(updated.shopId()).isEqualTo("123");
        assertThat(updated.shopName()).isEqualTo("新店名");
        assertThat(updated.clientId()).isEqualTo("client-1");
        assertThat(updated.clientSecretCipher()).isEqualTo("secret-cipher");
        assertThat(updated.accessTokenCipher()).isEqualTo("access-cipher");
        assertThat(updated.refreshTokenCipher()).isEqualTo("refresh-cipher");
        assertThat(updated.tokenExpiresAt()).isEqualTo(expiresAt);
        assertThat(updated.connectionStatus()).isEqualTo("CONNECTED");
        assertThat(updated.lastConnectedAt()).isEqualTo(connectedAt);
        assertThat(updated.syncEnabled()).isTrue();
        verify(cipher, never()).encrypt(anyString());
    }

    @Test
    void changingClientIdClearsTokensAndResetsStatusToUnverified() {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        Instant expiresAt = Instant.parse("2026-12-01T00:00:00Z");
        Instant connectedAt = Instant.parse("2026-11-01T08:00:00Z");
        YouzanShopConfig existing = new YouzanShopConfig(10L, 2L, "123", "旧店名", "client-1",
                "secret-cipher", "access-cipher", "refresh-cipher", expiresAt,
                "CONNECTED", true, "old-err", connectedAt, Instant.parse("2026-10-01T00:00:00Z"), 3L);
        when(idempotency.lookup(eq(2L), eq("youzan.shop.config"), eq("key-2"), anyString())).thenReturn(Optional.empty());
        when(repository.findShopConfigForUpdate(2L, "123")).thenReturn(Optional.of(existing));
        when(repository.updateShopConfig(eq(2L), eq(10L), eq(3L), any())).thenReturn(1);
        when(repository.findShopConfig(2L, "123")).thenReturn(Optional.of(new YouzanShopConfig(10L, 2L, "123", "旧店名",
                "client-2", "secret-cipher", null, null, null, "UNVERIFIED", true, null, null, existing.createdAt(), 4L)));
        YouzanConnectionService service = service(repository, cipher, idempotency);

        service.saveShop(admin(), new YouzanShopConfigRequest("123", "旧店名", " client-2 ", null, true, 3L), "key-2");

        ArgumentCaptor<YouzanShopConfig> captor = ArgumentCaptor.forClass(YouzanShopConfig.class);
        verify(repository).updateShopConfig(eq(2L), eq(10L), eq(3L), captor.capture());
        YouzanShopConfig updated = captor.getValue();
        assertThat(updated.clientId()).isEqualTo("client-2");
        assertThat(updated.accessTokenCipher()).isNull();
        assertThat(updated.refreshTokenCipher()).isNull();
        assertThat(updated.tokenExpiresAt()).isNull();
        assertThat(updated.connectionStatus()).isEqualTo("UNVERIFIED");
        assertThat(updated.lastError()).isNull();
        assertThat(updated.lastConnectedAt()).isNull();
    }

    @Test
    void changingClientSecretClearsTokensResetsStatusAndEncryptsTrimmedSecret() {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        Instant expiresAt = Instant.parse("2026-12-01T00:00:00Z");
        Instant connectedAt = Instant.parse("2026-11-01T08:00:00Z");
        YouzanShopConfig existing = new YouzanShopConfig(10L, 2L, "123", "旧店名", "client-1",
                "old-secret-cipher", "access-cipher", "refresh-cipher", expiresAt,
                "CONNECTED", true, "old-err", connectedAt, Instant.parse("2026-10-01T00:00:00Z"), 3L);
        when(idempotency.lookup(eq(2L), eq("youzan.shop.config"), eq("key-3"), anyString())).thenReturn(Optional.empty());
        when(repository.findShopConfigForUpdate(2L, "123")).thenReturn(Optional.of(existing));
        when(cipher.encrypt("new-secret")).thenReturn("new-secret-cipher");
        when(repository.updateShopConfig(eq(2L), eq(10L), eq(3L), any())).thenReturn(1);
        when(repository.findShopConfig(2L, "123")).thenReturn(Optional.of(new YouzanShopConfig(10L, 2L, "123", "旧店名",
                "client-1", "new-secret-cipher", null, null, null, "UNVERIFIED", true, null, null, existing.createdAt(), 4L)));
        YouzanConnectionService service = service(repository, cipher, idempotency);

        service.saveShop(admin(), new YouzanShopConfigRequest("123", "旧店名", "client-1", "  new-secret  ", true, 3L), "key-3");

        verify(cipher).encrypt("new-secret");
        ArgumentCaptor<YouzanShopConfig> captor = ArgumentCaptor.forClass(YouzanShopConfig.class);
        verify(repository).updateShopConfig(eq(2L), eq(10L), eq(3L), captor.capture());
        YouzanShopConfig updated = captor.getValue();
        assertThat(updated.clientSecretCipher()).isEqualTo("new-secret-cipher");
        assertThat(updated.accessTokenCipher()).isNull();
        assertThat(updated.refreshTokenCipher()).isNull();
        assertThat(updated.tokenExpiresAt()).isNull();
        assertThat(updated.connectionStatus()).isEqualTo("UNVERIFIED");
        assertThat(updated.lastError()).isNull();
        assertThat(updated.lastConnectedAt()).isNull();
    }

    @Test
    void creatingShopTrimsWhitespaceFromShopIdNameClientIdAndSecret() {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.lookup(eq(2L), eq("youzan.shop.config"), eq("key-new"), anyString())).thenReturn(Optional.empty());
        when(repository.findShopConfigForUpdate(2L, "shop-9")).thenReturn(Optional.empty());
        when(cipher.encrypt("sec-9")).thenReturn("enc-sec-9");
        when(repository.findShopConfig(2L, "shop-9")).thenReturn(Optional.of(new YouzanShopConfig(99L, 2L, "shop-9",
                "新店", "cli-9", "enc-sec-9", null, null, null, "UNVERIFIED", false, null, null, null, 0L)));
        YouzanConnectionService service = service(repository, cipher, idempotency);

        service.saveShop(admin(), new YouzanShopConfigRequest("  shop-9 ", " 新店  ", " cli-9 ", " sec-9 ", false, null), "key-new");

        verify(cipher).encrypt("sec-9");
        ArgumentCaptor<YouzanShopConfig> captor = ArgumentCaptor.forClass(YouzanShopConfig.class);
        verify(repository).insertShopConfig(eq(2L), captor.capture());
        YouzanShopConfig inserted = captor.getValue();
        assertThat(inserted.shopId()).isEqualTo("shop-9");
        assertThat(inserted.shopName()).isEqualTo("新店");
        assertThat(inserted.clientId()).isEqualTo("cli-9");
        assertThat(inserted.clientSecretCipher()).isEqualTo("enc-sec-9");
    }

    @Test
    void savePolicyTranslatesConcurrentInsertDuplicateKeyToConflict() {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        YouzanShopConfig shop = new YouzanShopConfig(1L, 2L, "123", "测试店", "client", "secret",
                null, null, null, "CONNECTED", true, null, null, null, 0L);
        when(repository.findShopConfig(2L, "123")).thenReturn(Optional.of(shop));
        when(repository.findSyncPolicy(2L, "123")).thenReturn(Optional.empty());
        doThrow(new DuplicateKeyException("uk_youzan_sync_policy_1")).when(repository).insertSyncPolicy(eq(2L), any());
        YouzanConnectionService service = service(repository, mock(AesGcmCipher.class), mock(IdempotencyService.class));

        assertThatThrownBy(() -> service.savePolicy(admin(), "123", new YouzanSyncPolicyRequest(
                Instant.parse("2026-01-01T00:00:00Z"), 300, 60, 50, true, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessage("同步策略已被并发创建，请重试");
    }

    private static YouzanConnectionService service(YouzanConfigRepository repository, AesGcmCipher cipher,
                                                   IdempotencyService idempotency) {
        return new YouzanConnectionService(repository, mock(YouzanTokenService.class), cipher, idempotency,
                mock(AuditService.class), new ObjectMapper().findAndRegisterModules(), transactionManager(),
                mock(IntegrationRuntimeConfigService.class));
    }

    private static CurrentUser admin() {
        return new CurrentUser(1, 2, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("integration:youzan:manage"), Set.of());
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            public void commit(TransactionStatus status) {
            }

            public void rollback(TransactionStatus status) {
            }
        };
    }
}
