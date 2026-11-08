package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.adapter.RealYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanOfficialMessageParser;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.application.YouzanEventService;
import com.whale.crm.integration.youzan.application.YouzanOfficialMessageService;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouzanOfficialMessageServiceTest {
    private static final String RAW = "{\"msgId\":\"m-1\",\"kdtId\":\"123\",\"msg\":{\"tid\":\"E-1\"}}";

    @Test
    void duplicateButUnprocessedMessageIsScheduledAgain() throws Exception {
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService events = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        YouzanShopConfig config = config();
        when(configs.findShopConfigByClientAndShop("client", "123")).thenReturn(Optional.of(config));
        when(cipher.decrypt("secret-cipher")).thenReturn("secret");
        YouzanEventResponse accepted = new YouzanEventResponse(9L, "m-1", "trade_TradePaid", "E-1",
                "RECEIVED", 0, null, null, Instant.now(), null, true);
        when(events.ingest(eq(2L), any(), eq("OFFICIAL_WEBHOOK"), eq(RAW))).thenReturn(accepted);
        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, mock(YouzanRepository.class),
                new YouzanOfficialMessageParser(new ObjectMapper()), mock(RealYouzanAdapter.class), events, cipher,
                scheduler, runtimeConfig());

        YouzanEventResponse result = service.receive("client", sign("client" + RAW + "secret"), "trade_TradePaid", RAW);

        assertThat(result.id()).isEqualTo(9L);
        verify(events).ingest(eq(2L), any(), eq("OFFICIAL_WEBHOOK"), eq(RAW));
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void invalidOfficialSignatureIsRejectedBeforeIngest() throws Exception {
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService events = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        when(configs.findShopConfigByClientAndShop("client", "123")).thenReturn(Optional.of(config()));
        when(cipher.decrypt("secret-cipher")).thenReturn("secret");
        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, mock(YouzanRepository.class),
                new YouzanOfficialMessageParser(new ObjectMapper()), mock(RealYouzanAdapter.class), events, cipher,
                mock(TaskScheduler.class), runtimeConfig());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.receive("client", "bad", "trade_TradePaid", RAW))
                .hasMessageContaining("签名无效");
        verify(events, never()).ingest(any(Long.class), any(), any(), any());
    }

    @Test
    void officialTestMessageNeverCreatesOrderWork() throws Exception {
        String raw = "{\"msgId\":\"m-test\",\"kdtId\":\"123\",\"test\":true,\"msg\":{\"tid\":\"E-1\"}}";
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService events = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        when(configs.findShopConfigByClientAndShop("client", "123")).thenReturn(Optional.of(config()));
        when(cipher.decrypt("secret-cipher")).thenReturn("secret");
        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, mock(YouzanRepository.class),
                new YouzanOfficialMessageParser(new ObjectMapper()), mock(RealYouzanAdapter.class), events, cipher,
                mock(TaskScheduler.class), runtimeConfig());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.receive("client", sign("client" + raw + "secret"), "trade_TradePaid", raw))
                .hasMessageContaining("测试消息");
        verify(events, never()).ingest(any(Long.class), any(), any(), any());
    }

    @Test
    void recoveryScansEnabledShopForDueEvents() {
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanRepository events = mock(YouzanRepository.class);
        when(configs.findSyncEnabledShopConfigs()).thenReturn(java.util.List.of(config()));
        when(events.findPendingEvents(eq(2L), eq("123"), any(Instant.class), eq(100))).thenReturn(java.util.List.of());
        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, events,
                new YouzanOfficialMessageParser(new ObjectMapper()), mock(RealYouzanAdapter.class),
                mock(YouzanEventService.class), mock(AesGcmCipher.class), mock(TaskScheduler.class), runtimeConfig());

        service.recoverPending();

        verify(events).findPendingEvents(eq(2L), eq("123"), any(Instant.class), eq(100));
    }

    private static IntegrationRuntimeConfigService runtimeConfig() {
        IntegrationRuntimeConfigService runtimeConfig = mock(IntegrationRuntimeConfigService.class);
        when(runtimeConfig.youzanRealEnabled(2L)).thenReturn(true);
        return runtimeConfig;
    }

    private static YouzanShopConfig config() {
        return new YouzanShopConfig(1L, 2L, "123", "测试店", "client", "secret-cipher",
                null, null, null, "CONNECTED", true, null, null, null, 0L);
    }

    private static String sign(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
