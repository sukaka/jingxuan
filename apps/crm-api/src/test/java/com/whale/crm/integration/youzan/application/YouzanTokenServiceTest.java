package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.youzan.adapter.YouzanApiException;
import com.whale.crm.integration.youzan.adapter.YouzanRemoteClient;
import com.whale.crm.integration.youzan.adapter.YouzanTokenResponse;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouzanTokenServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void accessTokenUsesRefreshFalseOnFirstFetchAndRefreshAccessTokenUsesRefreshTrue() {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        YouzanRemoteClient remoteClient = mock(YouzanRemoteClient.class);
        AesGcmCipher cipher = passthroughCipher();
        AtomicReference<YouzanShopConfig> stored = new AtomicReference<>(new YouzanShopConfig(
                1L, 2L, "123", "测试店", "client", "secret",
                null, null, null, "UNVERIFIED", true, null, null, Instant.now(), 0L));
        stubRepository(repository, stored);
        when(remoteClient.fetchSelfToken("client", "secret", "123", false))
                .thenReturn(new YouzanTokenResponse("token-initial", "refresh-1", Instant.now().plus(2, ChronoUnit.HOURS)));
        when(remoteClient.fetchSelfToken("client", "secret", "123", true))
                .thenReturn(new YouzanTokenResponse("token-forced", "refresh-2", Instant.now().plus(2, ChronoUnit.HOURS)));

        YouzanTokenService service = new YouzanTokenService(repository, remoteClient, cipher, transactionManager());

        String first = service.accessToken(2L, "123");
        String cached = service.accessToken(2L, "123");
        String forced = service.refreshAccessToken(2L, "123");

        assertThat(first).isEqualTo("token-initial");
        assertThat(cached).isEqualTo("token-initial");
        assertThat(forced).isEqualTo("token-forced");
        verify(remoteClient, times(1)).fetchSelfToken("client", "secret", "123", false);
        verify(remoteClient, times(1)).fetchSelfToken("client", "secret", "123", true);
    }

    @Test
    void testConnectionAutoRefreshesOnceOnGatewayAuthErrorAndSucceeds() throws Exception {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        YouzanRemoteClient remoteClient = mock(YouzanRemoteClient.class);
        AesGcmCipher cipher = passthroughCipher();
        Instant previousConnected = Instant.parse("2026-10-01T08:00:00Z");
        AtomicReference<YouzanShopConfig> stored = new AtomicReference<>(new YouzanShopConfig(
                1L, 2L, "123", "测试店", "client", "secret",
                "stale-token", "old-refresh", Instant.now().plus(1, ChronoUnit.HOURS),
                "CONNECTED", true, null, previousConnected, previousConnected, 3L));
        stubRepository(repository, stored);
        YouzanApiException gatewayExpired = new YouzanApiException(ErrorCode.UNAUTHORIZED,
                "有赞授权或Token已失效", 4202, true, false, null);
        when(remoteClient.callApi(eq("stale-token"), eq("youzan.trades.sold.get"), eq("4.0.2"), any()))
                .thenThrow(gatewayExpired);
        when(remoteClient.fetchSelfToken("client", "secret", "123", true))
                .thenReturn(new YouzanTokenResponse("fresh-token", "new-refresh", Instant.now().plus(2, ChronoUnit.HOURS)));
        when(remoteClient.callApi(eq("fresh-token"), eq("youzan.trades.sold.get"), eq("4.0.2"), any()))
                .thenReturn(objectMapper.readTree("{\"success\":true,\"code\":200,\"data\":{\"total_results\":0}}"));

        YouzanTokenService service = new YouzanTokenService(repository, remoteClient, cipher, transactionManager());

        YouzanShopConfig result = service.testConnection(2L, "123");

        assertThat(result.connectionStatus()).isEqualTo("CONNECTED");
        assertThat(result.lastError()).isNull();
        assertThat(result.lastConnectedAt()).isAfter(previousConnected);
        verify(remoteClient, times(1)).fetchSelfToken("client", "secret", "123", true);
        verify(remoteClient).callApi(eq("stale-token"), eq("youzan.trades.sold.get"), eq("4.0.2"), any());
        verify(remoteClient).callApi(eq("fresh-token"), eq("youzan.trades.sold.get"), eq("4.0.2"), any());
        verify(repository).updateShopConnectionStatus(eq(2L), eq(1L), eq(4L), eq("CONNECTED"), isNull(), any(Instant.class));
    }

    @Test
    void testConnectionMarksFailedAndPreservesLastConnectedAtWhenRetryStillFails() {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        YouzanRemoteClient remoteClient = mock(YouzanRemoteClient.class);
        AesGcmCipher cipher = passthroughCipher();
        Instant previousConnected = Instant.parse("2026-10-01T08:00:00Z");
        AtomicReference<YouzanShopConfig> stored = new AtomicReference<>(new YouzanShopConfig(
                1L, 2L, "123", "测试店", "client", "secret",
                "stale-token", "old-refresh", Instant.now().plus(1, ChronoUnit.HOURS),
                "CONNECTED", true, null, previousConnected, previousConnected, 3L));
        stubRepository(repository, stored);
        YouzanApiException gatewayExpired = new YouzanApiException(ErrorCode.UNAUTHORIZED,
                "有赞授权或Token已失效", 4202, true, false, null);
        when(remoteClient.callApi(eq("stale-token"), eq("youzan.trades.sold.get"), eq("4.0.2"), any()))
                .thenThrow(gatewayExpired);
        when(remoteClient.fetchSelfToken("client", "secret", "123", true))
                .thenReturn(new YouzanTokenResponse("fresh-token", "new-refresh", Instant.now().plus(2, ChronoUnit.HOURS)));
        when(remoteClient.callApi(eq("fresh-token"), eq("youzan.trades.sold.get"), eq("4.0.2"), any()))
                .thenThrow(gatewayExpired);

        YouzanTokenService service = new YouzanTokenService(repository, remoteClient, cipher, transactionManager());

        assertThatThrownBy(() -> service.testConnection(2L, "123"))
                .isSameAs(gatewayExpired);

        verify(remoteClient, times(1)).fetchSelfToken("client", "secret", "123", true);
        assertThat(stored.get().connectionStatus()).isEqualTo("FAILED");
        assertThat(stored.get().lastConnectedAt()).isNotNull();
        assertThat(stored.get().lastError()).isEqualTo("有赞授权或Token已失效");
    }

    @Test
    void markFailedPreservesHistoricalLastConnectedAtOnRateLimitError() {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        YouzanRemoteClient remoteClient = mock(YouzanRemoteClient.class);
        AesGcmCipher cipher = passthroughCipher();
        Instant previousConnected = Instant.parse("2026-10-01T08:00:00Z");
        AtomicReference<YouzanShopConfig> stored = new AtomicReference<>(new YouzanShopConfig(
                1L, 2L, "123", "测试店", "client", "secret",
                "valid-token", "refresh", Instant.now().plus(1, ChronoUnit.HOURS),
                "CONNECTED", true, null, previousConnected, previousConnected, 5L));
        stubRepository(repository, stored);
        YouzanApiException rateLimited = new YouzanApiException(ErrorCode.RATE_LIMITED,
                "有赞接口限流，请稍后重试", 4101, false, true, null);
        when(remoteClient.callApi(eq("valid-token"), eq("youzan.trades.sold.get"), eq("4.0.2"), any()))
                .thenThrow(rateLimited);

        YouzanTokenService service = new YouzanTokenService(repository, remoteClient, cipher, transactionManager());

        assertThatThrownBy(() -> service.testConnection(2L, "123"))
                .isSameAs(rateLimited);

        verify(remoteClient, never()).fetchSelfToken(anyString(), anyString(), anyString(), anyBoolean());
        verify(repository).updateShopConnectionStatus(2L, 1L, 5L, "FAILED", "有赞接口限流，请稍后重试", previousConnected);
        assertThat(stored.get().connectionStatus()).isEqualTo("FAILED");
        assertThat(stored.get().lastConnectedAt()).isEqualTo(previousConnected);
    }

    private static void stubRepository(YouzanConfigRepository repository, AtomicReference<YouzanShopConfig> stored) {
        when(repository.findShopConfig(2L, "123")).thenAnswer(inv -> Optional.of(stored.get()));
        when(repository.findShopConfigForUpdate(2L, "123")).thenAnswer(inv -> Optional.of(stored.get()));
        when(repository.updateShopTokens(eq(2L), eq(1L), anyLong(), any())).thenAnswer(inv -> {
            long version = inv.getArgument(2);
            YouzanShopConfig updated = inv.getArgument(3);
            if (!stored.get().version().equals(version)) {
                return 0;
            }
            stored.set(new YouzanShopConfig(updated.id(), updated.tenantId(), updated.shopId(), updated.shopName(),
                    updated.clientId(), updated.clientSecretCipher(), updated.accessTokenCipher(),
                    updated.refreshTokenCipher(), updated.tokenExpiresAt(), updated.connectionStatus(),
                    updated.syncEnabled(), updated.lastError(), updated.lastConnectedAt(), updated.createdAt(),
                    version + 1));
            return 1;
        });
        when(repository.updateShopConnectionStatus(eq(2L), eq(1L), anyLong(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    long version = inv.getArgument(2);
                    String status = inv.getArgument(3);
                    String error = inv.getArgument(4);
                    Instant connectedAt = inv.getArgument(5);
                    YouzanShopConfig current = stored.get();
                    if (!current.version().equals(version)) {
                        return 0;
                    }
                    stored.set(new YouzanShopConfig(current.id(), current.tenantId(), current.shopId(),
                            current.shopName(), current.clientId(), current.clientSecretCipher(),
                            current.accessTokenCipher(), current.refreshTokenCipher(), current.tokenExpiresAt(),
                            status, current.syncEnabled(), error, connectedAt, current.createdAt(), version + 1));
                    return 1;
                });
    }

    private static AesGcmCipher passthroughCipher() {
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        when(cipher.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(cipher.encrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return cipher;
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
