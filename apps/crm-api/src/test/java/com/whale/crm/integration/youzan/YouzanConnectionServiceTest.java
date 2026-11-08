package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.application.YouzanConnectionService;
import com.whale.crm.integration.youzan.application.YouzanTokenService;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YouzanConnectionServiceTest {
    @Test
    void shopViewContainsOnlySecretStatusAndNeverCipherValues() {
        YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
        YouzanShopConfig config = new YouzanShopConfig(1L, 2L, "123", "虚构店铺", "client",
                "secret-cipher-value", "access-token-cipher-value", "refresh-cipher-value", null,
                "UNVERIFIED", false, null, null, null, 0L);
        when(repository.findShopConfigs(2L)).thenReturn(List.of(config));
        YouzanConnectionService service = new YouzanConnectionService(repository, mock(YouzanTokenService.class),
                mock(AesGcmCipher.class), mock(IdempotencyService.class), mock(AuditService.class),
                new ObjectMapper(), mock(PlatformTransactionManager.class), mock(IntegrationRuntimeConfigService.class));

        String json = new ObjectMapper().valueToTree(service.shops(2L)).toString();

        assertThat(json).contains("secretConfigured").contains("client");
        assertThat(json).doesNotContain("secret-cipher-value", "access-token-cipher-value", "refresh-cipher-value");
    }
}
