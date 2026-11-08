package com.whale.crm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.DifyKnowledgeConfigRequest;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DifyDatasetConfigServiceTest {
    private static final String APP_KEY = "dify-app-synthetic-key";
    private static final String DATASET_KEY = "dify-dataset-synthetic-key";
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
    void datasetKeyIsEncryptedIndependentlyAndSyncStateIsReadable() {
        DifyKnowledgeConfig saved = new DifyKnowledgeConfig(1L, 1L, "dify", "https://dify.example.com/v1",
                cipher.encrypt(APP_KEY), "dataset-1", cipher.encrypt(DATASET_KEY), true, 30, false,
                "UNVERIFIED", null, null, Instant.now(), Instant.now(), 0L);
        when(repository.findForUpdate(1L)).thenReturn(Optional.empty());
        when(repository.find(1L)).thenReturn(Optional.of(saved));
        DifyKnowledgeConfigRequest request = new DifyKnowledgeConfigRequest("dify", "https://dify.example.com/v1",
                APP_KEY, "dataset-1", DATASET_KEY, 30, false, true, 0L);

        var response = service.save(admin, request, "dataset-save");

        assertThat(response.datasetId()).isEqualTo("dataset-1");
        assertThat(response.datasetApiKeyConfigured()).isTrue();
        assertThat(response.syncEnabled()).isTrue();
        assertThat(response.toString()).doesNotContain(APP_KEY, DATASET_KEY,
                saved.appApiKeyCipher(), saved.datasetApiKeyCipher());
        verify(repository).insert(eq(1L), argThat(value ->
                !DATASET_KEY.equals(value.datasetApiKeyCipher()) && !APP_KEY.equals(value.appApiKeyCipher())));
        assertThat(service.syncEnabled(1L)).isTrue();
        assertThat(service.datasetId(1L)).isEqualTo("dataset-1");
    }

    @Test
    void difySyncRequiresDatasetIdAndDatasetKey() {
        DifyKnowledgeConfigRequest incomplete = new DifyKnowledgeConfigRequest("dify", "https://dify.example.com/v1",
                APP_KEY, null, null, 30, false, true, 0L);

        assertThatThrownBy(() -> service.save(admin, incomplete, "dataset-incomplete"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Dataset");
        verify(repository, never()).insert(anyLong(), any());
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
