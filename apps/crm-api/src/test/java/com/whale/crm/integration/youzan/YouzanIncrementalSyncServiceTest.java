package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.adapter.YouzanSyncCursorValue;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncResponse;
import com.whale.crm.integration.youzan.application.YouzanIncrementalSyncService;
import com.whale.crm.integration.youzan.application.YouzanManagementService;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.integration.youzan.domain.YouzanSyncCursor;
import com.whale.crm.integration.youzan.domain.YouzanSyncPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void initialGapOver90DaysIsClampedTo90DayWindowWithSecondPrecision() throws Exception {
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanManagementService management = mock(YouzanManagementService.class);
        IntegrationRuntimeConfigService runtimeConfig = mock(IntegrationRuntimeConfigService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        when(runtimeConfig.youzanRealEnabled(2L)).thenReturn(true);
        YouzanIncrementalSyncService service = new YouzanIncrementalSyncService(configs, repository, management,
                mapper, runtimeConfig);
        YouzanShopConfig shop = new YouzanShopConfig(1L, 2L, "123", "测试店", "client", "secret",
                null, null, null, "CONNECTED", true, null, null, null, 0L);
        Instant initialStart = Instant.now().minus(Duration.ofDays(120)).plusMillis(789);
        YouzanSyncPolicy due = new YouzanSyncPolicy(1L, "123", initialStart, 300, 60, 50,
                true, Instant.now().minusSeconds(1), null, 0L);
        when(configs.findSyncEnabledShopConfigs()).thenReturn(List.of(shop));
        when(configs.findSyncPolicy(2L, "123")).thenReturn(Optional.of(due));
        when(repository.findCursor(2L, "123")).thenReturn(Optional.empty());
        when(configs.markPolicyScheduled(eq(2L), eq(1L), eq(0L), any(Instant.class), any(Instant.class))).thenReturn(1);
        when(management.syncScheduled(eq(2L), eq("123"), anyString(), eq(50)))
                .thenReturn(new YouzanSyncResponse(0, 0, 0, 0, "1", false));

        service.runDuePolicies();

        ArgumentCaptor<String> cursorCaptor = ArgumentCaptor.forClass(String.class);
        verify(management).syncScheduled(eq(2L), eq("123"), cursorCaptor.capture(), eq(50));
        YouzanSyncCursorValue cursor = mapper.readValue(cursorCaptor.getValue(), YouzanSyncCursorValue.class);
        Instant expectedStart = initialStart.minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        assertThat(cursor.queryType()).isEqualTo("UPDATED");
        assertThat(cursor.pageNo()).isEqualTo(1);
        assertThat(cursor.windowStart()).isEqualTo(expectedStart);
        assertThat(cursor.windowEnd()).isEqualTo(expectedStart.plus(Duration.ofDays(90)));
        assertThat(cursor.windowEnd()).isBefore(cursor.finalEnd());
        assertThat(cursor.windowStart().getNano()).isZero();
        assertThat(cursor.windowEnd().getNano()).isZero();
        assertThat(cursor.finalEnd().getNano()).isZero();
    }

    @Test
    void recentCursorTruncatesToWholeSecondsWithoutClamping() throws Exception {
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanManagementService management = mock(YouzanManagementService.class);
        IntegrationRuntimeConfigService runtimeConfig = mock(IntegrationRuntimeConfigService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        when(runtimeConfig.youzanRealEnabled(2L)).thenReturn(true);
        YouzanIncrementalSyncService service = new YouzanIncrementalSyncService(configs, repository, management,
                mapper, runtimeConfig);
        YouzanShopConfig shop = new YouzanShopConfig(1L, 2L, "123", "测试店", "client", "secret",
                null, null, null, "CONNECTED", true, null, null, null, 0L);
        Instant lastSyncAt = Instant.now().minus(Duration.ofHours(2)).plusMillis(456);
        YouzanSyncPolicy due = new YouzanSyncPolicy(1L, "123", Instant.parse("2026-01-01T00:00:00Z"), 300, 30, 50,
                true, Instant.now().minusSeconds(1), null, 0L);
        when(configs.findSyncEnabledShopConfigs()).thenReturn(List.of(shop));
        when(configs.findSyncPolicy(2L, "123")).thenReturn(Optional.of(due));
        when(repository.findCursor(2L, "123")).thenReturn(Optional.of(
                new YouzanSyncCursor(9L, "123", "{}", null, null, lastSyncAt, null, null, 1L)));
        when(configs.markPolicyScheduled(eq(2L), eq(1L), eq(0L), any(Instant.class), any(Instant.class))).thenReturn(1);
        when(management.syncScheduled(eq(2L), eq("123"), anyString(), eq(50)))
                .thenReturn(new YouzanSyncResponse(0, 0, 0, 0, "1", false));

        service.runDuePolicies();

        ArgumentCaptor<String> cursorCaptor = ArgumentCaptor.forClass(String.class);
        verify(management).syncScheduled(eq(2L), eq("123"), cursorCaptor.capture(), eq(50));
        YouzanSyncCursorValue cursor = mapper.readValue(cursorCaptor.getValue(), YouzanSyncCursorValue.class);
        assertThat(cursor.windowStart()).isEqualTo(lastSyncAt.minusSeconds(30).truncatedTo(ChronoUnit.SECONDS));
        assertThat(cursor.windowEnd()).isEqualTo(cursor.finalEnd());
        assertThat(cursor.windowStart().getNano()).isZero();
        assertThat(cursor.windowEnd().getNano()).isZero();
        assertThat(cursor.finalEnd().getNano()).isZero();
    }

    @Test
    void incompletePersistedCursorIsResumedInsteadOfDiscarded() throws Exception {
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanManagementService management = mock(YouzanManagementService.class);
        IntegrationRuntimeConfigService runtimeConfig = mock(IntegrationRuntimeConfigService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        when(runtimeConfig.youzanRealEnabled(2L)).thenReturn(true);
        YouzanIncrementalSyncService service = new YouzanIncrementalSyncService(configs, repository, management,
                mapper, runtimeConfig);
        YouzanShopConfig shop = new YouzanShopConfig(1L, 2L, "123", "测试店", "client", "secret",
                null, null, null, "CONNECTED", true, null, null, null, 0L);
        YouzanSyncPolicy due = new YouzanSyncPolicy(1L, "123", Instant.parse("2026-01-01T00:00:00Z"), 300, 60, 50,
                true, Instant.now().minusSeconds(1), null, 0L);
        when(configs.findSyncEnabledShopConfigs()).thenReturn(List.of(shop));
        when(configs.findSyncPolicy(2L, "123")).thenReturn(Optional.of(due));
        when(configs.markPolicyScheduled(eq(2L), eq(1L), eq(0L), any(Instant.class), any(Instant.class))).thenReturn(1);
        when(management.syncScheduled(eq(2L), eq("123"), anyString(), eq(50)))
                .thenReturn(new YouzanSyncResponse(0, 0, 0, 0, "1", false));

        // Case 1: Mid-window pageNo > 1 with lastSyncAt before windowEnd
        Instant start = Instant.parse("2026-02-01T00:00:00Z");
        Instant end = Instant.parse("2026-03-01T00:00:00Z");
        String midPageCursor = mapper.writeValueAsString(new YouzanSyncCursorValue("UPDATED", start, end, end, 4));
        when(repository.findCursor(2L, "123")).thenReturn(Optional.of(
                new YouzanSyncCursor(9L, "123", midPageCursor, start, end, start, null, null, 1L)));

        service.runDuePolicies();
        verify(management).syncScheduled(2L, "123", midPageCursor, 50);

        // Case 2: Sub-window remaining (windowEnd < finalEnd) with pageNo == 1
        Instant subStart = Instant.parse("2026-03-01T00:00:00Z");
        Instant subEnd = Instant.parse("2026-05-30T00:00:00Z");
        Instant finalEnd = Instant.parse("2026-07-01T00:00:00Z");
        String subWindowCursor = mapper.writeValueAsString(new YouzanSyncCursorValue("UPDATED", subStart, subEnd, finalEnd, 1));
        when(repository.findCursor(2L, "123")).thenReturn(Optional.of(
                new YouzanSyncCursor(9L, "123", subWindowCursor, subStart, subEnd, subStart, null, null, 2L)));

        service.runDuePolicies();
        verify(management).syncScheduled(2L, "123", subWindowCursor, 50);
    }

    @Test
    void completedMultiPageCursorStartsFreshIncrementalWindowInsteadOfLooping() throws Exception {
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanManagementService management = mock(YouzanManagementService.class);
        IntegrationRuntimeConfigService runtimeConfig = mock(IntegrationRuntimeConfigService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        when(runtimeConfig.youzanRealEnabled(2L)).thenReturn(true);
        YouzanIncrementalSyncService service = new YouzanIncrementalSyncService(configs, repository, management,
                mapper, runtimeConfig);
        YouzanShopConfig shop = new YouzanShopConfig(1L, 2L, "123", "测试店", "client", "secret",
                null, null, null, "CONNECTED", true, null, null, null, 0L);
        YouzanSyncPolicy due = new YouzanSyncPolicy(1L, "123", Instant.parse("2026-01-01T00:00:00Z"), 300, 60, 50,
                true, Instant.now().minusSeconds(1), null, 0L);
        Instant start = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        Instant end = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        String completedCursor = mapper.writeValueAsString(new YouzanSyncCursorValue("UPDATED", start, end, end, 4));
        when(configs.findSyncEnabledShopConfigs()).thenReturn(List.of(shop));
        when(configs.findSyncPolicy(2L, "123")).thenReturn(Optional.of(due));
        when(repository.findCursor(2L, "123")).thenReturn(Optional.of(
                new YouzanSyncCursor(9L, "123", completedCursor, start, end, end, null, null, 3L)));
        when(configs.markPolicyScheduled(eq(2L), eq(1L), eq(0L), any(Instant.class), any(Instant.class))).thenReturn(1);
        when(management.syncScheduled(eq(2L), eq("123"), anyString(), eq(50)))
                .thenReturn(new YouzanSyncResponse(0, 0, 0, 0, "1", false));

        service.runDuePolicies();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(management).syncScheduled(eq(2L), eq("123"), captor.capture(), eq(50));
        YouzanSyncCursorValue built = mapper.readValue(captor.getValue(), YouzanSyncCursorValue.class);
        assertThat(built.pageNo()).isEqualTo(1);
        assertThat(built.windowStart()).isEqualTo(end.minusSeconds(60));
        assertThat(built.windowEnd()).isAfter(end);
    }

    private static YouzanSyncPolicy policy(Instant nextRunAt) {
        return new YouzanSyncPolicy(1L, "123", Instant.parse("2026-01-01T00:00:00Z"), 300, 60, 50,
                true, nextRunAt, null, 0L);
    }
}
