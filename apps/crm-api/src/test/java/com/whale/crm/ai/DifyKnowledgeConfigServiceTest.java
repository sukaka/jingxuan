package com.whale.crm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.DifyKnowledgeConfigRequest;
import com.whale.crm.ai.application.AiWorkerException;
import com.whale.crm.ai.application.DifyKnowledgeConfigService;
import com.whale.crm.ai.application.KnowledgeWorkerClient;
import com.whale.crm.ai.domain.DifyKnowledgeConfig;
import com.whale.crm.ai.domain.DifyKnowledgeConfigRepository;
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

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DifyKnowledgeConfigServiceTest {
    private static final String API_KEY = "dify-test-only-not-a-real-secret";
    @Mock DifyKnowledgeConfigRepository repository;
    @Mock IdempotencyService idempotencyService;
    @Mock AuditService auditService;
    @Mock KnowledgeWorkerClient workerClient;
    @Mock PlatformTransactionManager transactionManager;

    private AesGcmCipher cipher;
    private DifyKnowledgeConfigService service;
    private final CurrentUser admin = new CurrentUser(1, 1, "admin", "管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("ai:knowledge:config"), Set.of());

    @BeforeEach
    void setUp() {
        cipher = new AesGcmCipher(new CryptoProperties("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "test-pepper"));
        service = new DifyKnowledgeConfigService(repository, cipher, idempotencyService, auditService, workerClient,
                new ObjectMapper().findAndRegisterModules(), transactionManager, false);
    }

    @Test
    void saveEncryptsKeyAndAuditAndResponseDoNotContainSecrets() {
        DifyKnowledgeConfig saved = config(0L, cipher.encrypt(API_KEY), "UNVERIFIED", null);
        when(repository.findForUpdate(1L)).thenReturn(Optional.empty());
        when(repository.find(1L)).thenReturn(Optional.of(saved));

        var response = service.save(admin, request(0L), "save-key");

        assertThat(response.apiKeyConfigured()).isTrue();
        assertThat(response.toString()).doesNotContain(API_KEY, saved.appApiKeyCipher());
        verify(repository).insert(eq(1L), argThat(value -> !API_KEY.equals(value.appApiKeyCipher())));
        verify(auditService).record(eq(1L), eq(1L), eq("DIFY_KNOWLEDGE_CONFIG_SAVE"), eq("DIFY_KNOWLEDGE_CONFIG"),
                anyString(), any(), argThat(snapshot -> !snapshot.toString().contains(API_KEY)
                        && !snapshot.toString().contains(saved.appApiKeyCipher())), any());
    }

    @Test
    void blankKeyRetainsExistingCipherAndStaleVersionIsRejected() {
        DifyKnowledgeConfig existing = config(4L, cipher.encrypt(API_KEY), "CONNECTED", Instant.now());
        DifyKnowledgeConfig updated = config(5L, existing.appApiKeyCipher(), "UNVERIFIED", null);
        when(repository.findForUpdate(1L)).thenReturn(Optional.of(existing));
        when(repository.find(1L)).thenReturn(Optional.of(updated));
        when(repository.update(eq(1L), eq(1L), eq(4L), any())).thenReturn(1);
        DifyKnowledgeConfigRequest blankKey = new DifyKnowledgeConfigRequest("dify", existing.baseUrl(), null,
                null, null, 45, true, false, 4L);

        service.save(admin, blankKey, "blank-key");

        verify(repository).update(eq(1L), eq(1L), eq(4L), argThat(value ->
                existing.appApiKeyCipher().equals(value.appApiKeyCipher()) && value.timeoutSeconds() == 45));
        assertThatThrownBy(() -> service.save(admin, request(1L), "stale-key"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("版本");
        verify(repository, never()).update(eq(1L), eq(1L), eq(1L), any());
    }

    @Test
    void insecureHttpAndDisabledEnabledCombinationAreRejected() {
        assertThatThrownBy(() -> service.save(admin, new DifyKnowledgeConfigRequest("dify",
                "http://public.example.com/v1", API_KEY, null, null, 30, true, false, 0L), "http-key"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> service.save(admin, new DifyKnowledgeConfigRequest("disabled",
                null, null, null, null, 30, true, false, 0L), "disabled-key"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("disabled");
        verify(repository, never()).insert(anyLong(), any());
    }

    @Test
    void syncRequiresDatasetCredentials() {
        assertThatThrownBy(() -> service.save(admin, new DifyKnowledgeConfigRequest("dify",
                "https://dify.example.com/v1", API_KEY, "dataset-test", null, 30, true, true, 0L), "sync-key"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Dataset API Key");
        verify(repository, never()).insert(anyLong(), any());
    }

    @Test
    void connectionReceivesPlaintextOnlyAtWorkerBoundaryAndAdvancesVersion() {
        DifyKnowledgeConfig existing = config(2L, cipher.encrypt(API_KEY), "FAILED", null);
        Instant connectedAt = Instant.now();
        DifyKnowledgeConfig connected = new DifyKnowledgeConfig(1L, 1L, "dify", existing.baseUrl(),
                existing.appApiKeyCipher(), null, null, false, 30, true, "CONNECTED", null, connectedAt,
                existing.createdAt(), Instant.now(), 3L);
        when(repository.find(1L)).thenReturn(Optional.of(existing), Optional.of(connected));
        when(repository.updateConnection(eq(1L), eq(1L), eq(2L), eq("CONNECTED"), eq(null), any())).thenReturn(1);

        var response = service.testConnection(admin, "test-key", "req-1");

        verify(workerClient).testConnection(argThat(value -> API_KEY.equals(value.apiKey())
                && !value.apiKey().equals(existing.appApiKeyCipher())), eq(1L), eq("req-1"));
        assertThat(response.connectionStatus()).isEqualTo("CONNECTED");
        assertThat(response.version()).isEqualTo(3L);
        assertThat(response.toString()).doesNotContain(API_KEY, existing.appApiKeyCipher());
    }

    @Test
    void failedConnectionStoresOnlyMaskedError() {
        DifyKnowledgeConfig existing = config(3L, cipher.encrypt(API_KEY), "UNVERIFIED", null);
        DifyKnowledgeConfig failed = new DifyKnowledgeConfig(1L, 1L, "dify", existing.baseUrl(),
                existing.appApiKeyCipher(), null, null, false, 30, true, "FAILED", "Dify 知识服务连接失败，请检查配置或网络", null,
                existing.createdAt(), Instant.now(), 4L);
        when(repository.find(1L)).thenReturn(Optional.of(existing), Optional.of(failed));
        doThrow(new AiWorkerException("upstream leaked " + API_KEY, false))
                .when(workerClient).testConnection(any(), eq(1L), eq("req-1"));
        when(repository.updateConnection(eq(1L), eq(1L), eq(3L), eq("FAILED"), anyString(), eq(null))).thenReturn(1);

        var response = service.testConnection(admin, "test-key", "req-1");

        assertThat(response.connectionStatus()).isEqualTo("FAILED");
        assertThat(response.lastErrorMasked()).doesNotContain(API_KEY, "upstream");
    }

    private DifyKnowledgeConfigRequest request(long version) {
        return new DifyKnowledgeConfigRequest("dify", "https://dify.example.com/v1", API_KEY,
                null, null, 30, true, false, version);
    }

    private DifyKnowledgeConfig config(long version, String keyCipher, String status, Instant connectedAt) {
        return new DifyKnowledgeConfig(1L, 1L, "dify", "https://dify.example.com/v1", keyCipher,
                null, null, false, 30, true, status, null, connectedAt, Instant.now(), Instant.now(), version);
    }
}
