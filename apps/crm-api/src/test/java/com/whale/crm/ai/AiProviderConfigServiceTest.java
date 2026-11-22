package com.whale.crm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.AiProviderConfigRequest;
import com.whale.crm.ai.application.AiProviderConfigService;
import com.whale.crm.ai.application.AiWorkerClient;
import com.whale.crm.ai.application.AiWorkerException;
import com.whale.crm.ai.domain.AiProviderConfig;
import com.whale.crm.ai.domain.AiProviderConfigRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CryptoProperties;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiProviderConfigServiceTest {
    private static final String API_KEY = "sk-test-only-not-a-real-secret";
    @Mock AiProviderConfigRepository repository;
    @Mock IdempotencyService idempotencyService;
    @Mock AuditService auditService;
    @Mock AiWorkerClient workerClient;
    @Mock PlatformTransactionManager transactionManager;

    private AesGcmCipher cipher;
    private AiProviderConfigService service;
    private final CurrentUser admin = new CurrentUser(1, 1, "admin", "管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("ai:config"), Set.of());

    @BeforeEach
    void setUp() {
        cipher = new AesGcmCipher(new CryptoProperties("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "test-pepper"));
        service = new AiProviderConfigService(repository, cipher, idempotencyService, auditService, workerClient,
                new ObjectMapper().findAndRegisterModules(), transactionManager, false, false);
    }

    @Test
    void saveEncryptsKeyAndOnlyWorkerReceivesPlaintext() {
        AiProviderConfig saved = config(0L, cipher.encrypt(API_KEY), "UNVERIFIED", null);
        when(repository.findForUpdate(1L)).thenReturn(Optional.empty());
        when(repository.find(1L)).thenReturn(Optional.of(saved));

        var response = service.save(admin, request(0L), "save-key");

        assertThat(response.apiKeyConfigured()).isTrue();
        assertThat(response.toString()).doesNotContain(API_KEY, saved.apiKeyCipher());
        verify(repository).insert(eq(1L), any());
        verify(auditService).record(eq(1L), eq(1L), eq("AI_PROVIDER_CONFIG_SAVE"), eq("AI_PROVIDER_CONFIG"),
                anyString(), any(), any(), any());
        assertThat(service.workerConfig(1L).apiKey()).isEqualTo(API_KEY);
    }

    @Test
    void staleVersionAndInsecureHttpAreRejected() {
        when(repository.findForUpdate(1L)).thenReturn(Optional.of(config(2L, cipher.encrypt(API_KEY), "CONNECTED", Instant.now())));
        assertThatThrownBy(() -> service.save(admin, request(1L), "stale-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本");
        verify(repository, never()).update(eq(1L), eq(1L), eq(2L), any());

        assertThatThrownBy(() -> service.save(admin, new AiProviderConfigRequest("openai_compatible",
                "http://public.example.com/v1", API_KEY, "model", 30, true, 0L), "http-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void explicitlyAllowedInternalHttpIsAcceptedButPublicHttpIsNot() {
        service = new AiProviderConfigService(repository, cipher, idempotencyService, auditService, workerClient,
                new ObjectMapper().findAndRegisterModules(), transactionManager, true, Set.of(), false,
                host -> new InetAddress[]{InetAddress.getByName(host)});
        AiProviderConfigRequest internal = new AiProviderConfigRequest("openai_compatible",
                "http://127.0.0.1:9000/v1", API_KEY, "model", 30, true, 0L);
        assertThatThrownBy(() -> service.save(admin, internal, "unlisted-http"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内网");

        service = new AiProviderConfigService(repository, cipher, idempotencyService, auditService, workerClient,
                new ObjectMapper().findAndRegisterModules(), transactionManager, true, Set.of("127.0.0.1"), false,
                host -> new InetAddress[]{InetAddress.getByName(host)});
        AiProviderConfig saved = new AiProviderConfig(1L, 1L, "openai_compatible", "http://127.0.0.1:9000/v1",
                cipher.encrypt(API_KEY), "model", 30, true, "UNVERIFIED", null, null,
                Instant.now(), Instant.now(), 0L);
        when(repository.findForUpdate(1L)).thenReturn(Optional.empty());
        when(repository.find(1L)).thenReturn(Optional.of(saved));

        assertThat(service.save(admin, internal, "internal-http").baseUrl()).isEqualTo("http://127.0.0.1:9000/v1");
        assertThatThrownBy(() -> service.save(admin, new AiProviderConfigRequest("openai_compatible",
                "http://8.8.8.8/v1", API_KEY, "model", 30, true, 0L), "public-http"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内网");
        assertThatThrownBy(() -> service.save(admin, new AiProviderConfigRequest("openai_compatible",
                "http://169.254.169.254/latest/meta-data", API_KEY, "model", 30, true, 0L), "metadata-http"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void httpsSsrfTargetsAreBlockedOnSaveTestConnectionAndWorkerConfig() {
        service = new AiProviderConfigService(repository, cipher, idempotencyService, auditService, workerClient,
                new ObjectMapper().findAndRegisterModules(), transactionManager, false, Set.of("169.254.169.254"),
                false, host -> switch (host) {
                    case "rebind.example.test" -> new InetAddress[]{InetAddress.getByName("10.0.0.8")};
                    case "provider.example.com" -> new InetAddress[]{InetAddress.getByName("93.184.216.34")};
                    default -> new InetAddress[]{InetAddress.getByName(host)};
                });

        for (String dangerousUrl : new String[]{
                "https://127.0.0.1/v1",
                "https://169.254.169.254/v1",
                "https://10.0.0.1/v1",
                "https://[::1]/v1",
                "https://[fc00::1]/v1",
                "https://100.64.0.1/v1",
                "https://224.0.0.1/v1",
                "https://rebind.example.test/v1",
                "https://metadata.google.internal/v1"
        }) {
            assertThatThrownBy(() -> service.save(admin, new AiProviderConfigRequest("openai_compatible",
                    dangerousUrl, API_KEY, "model", 30, true, 0L), "ssrf-" + dangerousUrl))
                    .isInstanceOf(BusinessException.class);
        }

        AiProviderConfig poisonedStored = new AiProviderConfig(1L, 1L, "openai_compatible",
                "https://169.254.169.254/v1", cipher.encrypt(API_KEY), "model", 30, true,
                "UNVERIFIED", null, null, Instant.now(), Instant.now(), 1L);
        AiProviderConfig poisonedFailed = new AiProviderConfig(1L, 1L, "openai_compatible",
                "https://169.254.169.254/v1", cipher.encrypt(API_KEY), "model", 30, true,
                "FAILED", "AI 模型地址不允许访问内网或元数据地址", null,
                poisonedStored.createdAt(), Instant.now(), 2L);
        when(repository.find(1L)).thenReturn(
                Optional.of(poisonedStored), Optional.of(poisonedStored), Optional.of(poisonedFailed));
        assertThatThrownBy(() -> service.workerConfig(1L)).isInstanceOf(BusinessException.class);
        when(repository.updateConnection(eq(1L), eq(1L), eq(1L), eq("FAILED"), anyString(), eq(null))).thenReturn(1);
        var testResp = service.testConnection(admin, "test-ssrf");
        assertThat(testResp.connectionStatus()).isEqualTo("FAILED");
        verify(repository).updateConnection(eq(1L), eq(1L), eq(1L), eq("FAILED"), anyString(), eq(null));
        verify(workerClient, never()).testConnection(any());
    }

    @Test
    void failedConnectionStoresOnlyMaskedError() {
        AiProviderConfig existing = config(3L, cipher.encrypt(API_KEY), "UNVERIFIED", null);
        AiProviderConfig failed = new AiProviderConfig(1L, 1L, "openai_compatible", "https://provider.example.com/v1",
                existing.apiKeyCipher(), "model", 30, true, "FAILED", "AI 模型连接失败，请检查配置或网络", null,
                existing.createdAt(), Instant.now(), 4L);
        when(repository.find(1L)).thenReturn(Optional.of(existing), Optional.of(failed));
        org.mockito.Mockito.doThrow(new AiWorkerException("upstream leaked " + API_KEY, false))
                .when(workerClient).testConnection(any());
        when(repository.updateConnection(eq(1L), eq(1L), eq(3L), eq("FAILED"), anyString(), eq(null))).thenReturn(1);

        var response = service.testConnection(admin, "test-key");

        assertThat(response.connectionStatus()).isEqualTo("FAILED");
        assertThat(response.lastErrorMasked()).doesNotContain(API_KEY, "upstream");
        verify(repository).updateConnection(eq(1L), eq(1L), eq(3L), eq("FAILED"),
                eq("AI 模型连接失败，请检查配置或网络"), eq(null));
    }

    @Test
    void successfulConnectionAdvancesVersionAndClearsMaskedError() {
        AiProviderConfig existing = config(2L, cipher.encrypt(API_KEY), "FAILED", null);
        Instant connectedAt = Instant.now();
        AiProviderConfig connected = new AiProviderConfig(1L, 1L, "openai_compatible", "https://provider.example.com/v1",
                existing.apiKeyCipher(), "model", 30, true, "CONNECTED", null, connectedAt,
                existing.createdAt(), Instant.now(), 3L);
        when(repository.find(1L)).thenReturn(Optional.of(existing), Optional.of(connected));
        when(repository.updateConnection(eq(1L), eq(1L), eq(2L), eq("CONNECTED"), eq(null), any())).thenReturn(1);

        var response = service.testConnection(admin, "connected-key");

        assertThat(response.connectionStatus()).isEqualTo("CONNECTED");
        assertThat(response.lastErrorMasked()).isNull();
        assertThat(response.lastConnectedAt()).isEqualTo(connectedAt);
        assertThat(response.version()).isEqualTo(3L);
    }

    @Test
    void webEnabledOverridesFalseEnvironmentDefault() {
        when(repository.find(1L)).thenReturn(Optional.of(config(1L, cipher.encrypt(API_KEY), "CONNECTED", Instant.now())));

        assertThat(service.workerEnabled(1L)).isTrue();
    }

    @Test
    void disabledWebSwitchStopsWorkEvenWhenEnvironmentDefaultIsTrue() {
        service = new AiProviderConfigService(repository, cipher, idempotencyService, auditService, workerClient,
                new ObjectMapper().findAndRegisterModules(), transactionManager, false, true);
        AiProviderConfig disabled = new AiProviderConfig(1L, 1L, "openai_compatible", "https://provider.example.com/v1",
                cipher.encrypt(API_KEY), "model", 30, false, "CONNECTED", null, Instant.now(),
                Instant.now(), Instant.now(), 1L);
        when(repository.find(1L)).thenReturn(Optional.of(disabled));

        assertThat(service.workerEnabled(1L)).isFalse();
    }

    @Test
    void missingConfigFollowsEnvironmentDefaultAndOmitsDynamicConfig() {
        when(repository.find(2L)).thenReturn(Optional.empty());

        assertThat(service.workerEnabled(2L)).isFalse();
        service = new AiProviderConfigService(repository, cipher, idempotencyService, auditService, workerClient,
                new ObjectMapper().findAndRegisterModules(), transactionManager, false, true);
        assertThat(service.workerEnabled(2L)).isTrue();
        assertThat(service.workerConfig(2L)).isNull();
    }

    private AiProviderConfigRequest request(long version) {
        return new AiProviderConfigRequest("openai_compatible", "https://provider.example.com/v1", API_KEY,
                "model", 30, true, version);
    }

    private AiProviderConfig config(long version, String keyCipher, String status, Instant connectedAt) {
        return new AiProviderConfig(1L, 1L, "openai_compatible", "https://provider.example.com/v1", keyCipher,
                "model", 30, true, status, null, connectedAt, Instant.now(), Instant.now(), version);
    }
}
