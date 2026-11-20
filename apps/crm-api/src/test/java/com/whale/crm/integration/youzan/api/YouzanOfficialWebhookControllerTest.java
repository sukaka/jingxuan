package com.whale.crm.integration.youzan.api;

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
import com.whale.crm.platform.web.GlobalExceptionHandler;
import com.whale.crm.platform.web.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YouzanOfficialWebhookControllerTest {
    private static final String ENDPOINT = "/api/v1/webhooks/youzan/official";

    private YouzanConfigRepository configRepository;
    private YouzanEventService eventService;
    private AesGcmCipher cipher;
    private TaskScheduler taskScheduler;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        configRepository = mock(YouzanConfigRepository.class);
        eventService = mock(YouzanEventService.class);
        cipher = mock(AesGcmCipher.class);
        taskScheduler = mock(TaskScheduler.class);
        IntegrationRuntimeConfigService runtimeConfig = mock(IntegrationRuntimeConfigService.class);
        when(runtimeConfig.youzanRealEnabled(2L)).thenReturn(true);
        when(configRepository.findShopConfigByClientAndShop("client", "123")).thenReturn(Optional.of(shopConfig()));
        when(cipher.decrypt("secret-cipher")).thenReturn("secret");

        YouzanOfficialMessageService messageService = new YouzanOfficialMessageService(
                configRepository,
                mock(YouzanRepository.class),
                new YouzanOfficialMessageParser(new ObjectMapper()),
                mock(RealYouzanAdapter.class),
                eventService,
                cipher,
                taskScheduler,
                runtimeConfig);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new YouzanOfficialWebhookController(messageService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void validOrderPushReturnsYouzanAck200() throws Exception {
        String raw = "{\"msgId\":\"m-utf8\",\"kdtId\":\"123\",\"msg\":{\"tid\":\"E-100\",\"note\":\"中文原文字节\"}}";
        YouzanEventResponse accepted = new YouzanEventResponse(11L, "m-utf8", "trade_TradePaid", "E-100",
                "RECEIVED", 0, null, null, Instant.now(), null, false);
        when(eventService.ingest(eq(2L), any(), eq("OFFICIAL_WEBHOOK"), eq(raw))).thenReturn(accepted);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", "client")
                        .header("Event-Sign", sign("client" + raw + "secret"))
                        .header("Event-Type", "trade_TradePaid")
                        .content(raw.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(eventService).ingest(eq(2L), any(), eq("OFFICIAL_WEBHOOK"), eq(raw));
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void testPushWithValidSignatureReturnsAck200WithoutIngest() throws Exception {
        String raw = "{\"test\":true,\"kdt_id\":123}";

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", "client")
                        .header("Event-Sign", sign("client" + raw + "secret"))
                        .header("Event-Type", "trade_TradePaid")
                        .content(raw.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("success"));

        verify(eventService, never()).ingest(any(Long.class), any(), any(), any());
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void nonOrderPushWithValidSignatureReturnsAck200WithoutIngest() throws Exception {
        String raw = "{\"id\":\"m-item-1\",\"kdt_id\":123,\"msg\":{\"item_id\":999}}";

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", "client")
                        .header("Event-Sign", sign("client" + raw + "secret"))
                        .header("Event-Type", "item_ItemStateUpdate")
                        .content(raw.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("success"));

        verify(eventService, never()).ingest(any(Long.class), any(), any(), any());
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void invalidSignatureReturns401() throws Exception {
        String raw = "{\"msgId\":\"m-1\",\"kdtId\":\"123\",\"msg\":{\"tid\":\"E-1\"}}";

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", "client")
                        .header("Event-Sign", "invalid-sign")
                        .header("Event-Type", "trade_TradePaid")
                        .content(raw.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(eventService, never()).ingest(any(Long.class), any(), any(), any());
    }

    @Test
    void missingClientIdReturns400WithAccurateHeaderMessage() throws Exception {
        String raw = "{\"msgId\":\"m-1\",\"kdtId\":\"123\",\"msg\":{\"tid\":\"E-1\"}}";

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Event-Sign", "any")
                        .header("Event-Type", "trade_TradePaid")
                        .content(raw.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", containsString("Client-Id")))
                .andExpect(jsonPath("$.message", not(containsString("Idempotency-Key"))))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void missingEventSignReturns400WithAccurateHeaderMessage() throws Exception {
        String raw = "{\"msgId\":\"m-1\",\"kdtId\":\"123\",\"msg\":{\"tid\":\"E-1\"}}";

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", "client")
                        .header("Event-Type", "trade_TradePaid")
                        .content(raw.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", containsString("Event-Sign")))
                .andExpect(jsonPath("$.message", not(containsString("Idempotency-Key"))))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void missingEventTypeReturns400WithAccurateHeaderMessage() throws Exception {
        String raw = "{\"msgId\":\"m-1\",\"kdtId\":\"123\",\"msg\":{\"tid\":\"E-1\"}}";

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", "client")
                        .header("Event-Sign", "any")
                        .content(raw.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", containsString("Event-Type")))
                .andExpect(jsonPath("$.message", not(containsString("Idempotency-Key"))))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    private static YouzanShopConfig shopConfig() {
        return new YouzanShopConfig(1L, 2L, "123", "测试店", "client", "secret-cipher",
                null, null, null, "CONNECTED", true, null, null, null, 0L);
    }

    private static String sign(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
