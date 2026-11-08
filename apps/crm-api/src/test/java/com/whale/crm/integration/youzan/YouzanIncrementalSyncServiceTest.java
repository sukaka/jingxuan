package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncResponse;
import com.whale.crm.integration.youzan.application.YouzanIncrementalSyncService;
import com.whale.crm.integration.youzan.application.YouzanManagementService;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.integration.youzan.domain.YouzanSyncPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouzanIncrementalSyncServiceTest {
    @Test
    void nextRunAtControlsExecutionAndAdvancesAfterClaim() {
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanManagementService management = mock(YouzanManagementService.class);
        IntegrationRuntimeConfigService runtimeConfig = mock(IntegrationRuntimeConfigService.class);
        when(runtimeConfig.youzanRealEnabled(2L)).thenReturn(true);
        YouzanIncrementalSyncService service = new YouzanIncrementalSyncService(configs, repository, management,
                new ObjectMapper().findAndRegisterModules(), runtimeConfig);
        YouzanShopConfig shop = new YouzanShopConfig(1L, 2L, "123", "测试店", "client", "secret",
                null, null, null, "CONNECTED", true, null, null, null, 0L);
        when(configs.findSyncEnabledShopConfigs()).thenReturn(List.of(shop));
        Instant now = Instant.now();
        YouzanSyncPolicy future = policy(now.plusSeconds(3600));
        YouzanSyncPolicy due = policy(now.minusSeconds(1));
        when(configs.findSyncPolicy(2L, "123")).thenReturn(Optional.of(future), Optional.of(due));
        when(repository.findCursor(2L, "123")).thenReturn(Optional.empty());
        when(configs.markPolicyScheduled(eq(2L), eq(1L), eq(0L), any(Instant.class), any(Instant.class))).thenReturn(1);
        when(management.syncScheduled(eq(2L), eq("123"), anyString(), eq(50)))
                .thenReturn(new YouzanSyncResponse(0, 0, 0, 0, "1", false));

        service.runDuePolicies();
        verify(configs, never()).markPolicyScheduled(eq(2L), eq(1L), eq(0L), any(Instant.class), any(Instant.class));

        service.runDuePolicies();
        verify(configs).markPolicyScheduled(eq(2L), eq(1L), eq(0L), any(Instant.class), any(Instant.class));
    }

    private static YouzanSyncPolicy policy(Instant nextRunAt) {
        return new YouzanSyncPolicy(1L, "123", Instant.parse("2026-01-01T00:00:00Z"), 300, 60, 50,
                true, nextRunAt, null, 0L);
    }
}
