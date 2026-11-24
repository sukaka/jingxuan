package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.adapter.RealYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanMessage;
import com.whale.crm.integration.youzan.adapter.YouzanOfficialMessageParser;
import com.whale.crm.integration.youzan.adapter.YouzanOfficialSignature;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEnvelope;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.application.YouzanEventService;
import com.whale.crm.integration.youzan.application.YouzanOfficialMessageService;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        assertThatThrownBy(() -> service.receive("client", "bad", "trade_TradePaid", RAW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("签名无效")
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(events, never()).ingest(any(Long.class), any(), any(), any());
    }

    @Test
    void officialTestMessageNeverCreatesOrderWork() throws Exception {
        String raw = "{\"kdt_id\":123,\"test\":true,\"msg\":{\"tid\":\"E-1\"}}";
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService events = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(configs.findShopConfigByClientAndShop("client", "123")).thenReturn(Optional.of(config()));
        when(cipher.decrypt("secret-cipher")).thenReturn("secret");
        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, mock(YouzanRepository.class),
                new YouzanOfficialMessageParser(new ObjectMapper()), mock(RealYouzanAdapter.class), events, cipher,
                scheduler, runtimeConfig());

        YouzanEventResponse response = service.receive(
                "client", sign("client" + raw + "secret"), "trade_TradePaid", raw);

        assertThat(response).isNotNull();
        assertThat(response.processStatus()).isEqualTo("IGNORED");
        assertThat(response.eventId()).isEqualTo("TEST");
        verify(events, never()).ingest(any(Long.class), any(), any(), any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));

        assertThatThrownBy(() -> service.receive("client", "forged-sign", "trade_TradePaid", raw))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("签名无效")
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void nonOrderMessageReturnsCleanlyWithoutIngest() throws Exception {
        String raw = "{\"msgId\":\"m-item\",\"kdtId\":\"123\",\"msg\":{\"item_id\":55}}";
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService events = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(configs.findShopConfigByClientAndShop("client", "123")).thenReturn(Optional.of(config()));
        when(cipher.decrypt("secret-cipher")).thenReturn("secret");
        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, mock(YouzanRepository.class),
                new YouzanOfficialMessageParser(new ObjectMapper()), mock(RealYouzanAdapter.class), events, cipher,
                scheduler, runtimeConfig());

        YouzanEventResponse response = service.receive(
                "client", sign("client" + raw + "secret"), "item_ItemStateUpdate", raw);

        assertThat(response).isNotNull();
        assertThat(response.processStatus()).isEqualTo("IGNORED");
        verify(events, never()).ingest(any(Long.class), any(), any(), any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void testMessageSupportsDisabledSyncShopAndMissingOrPlaceholderKdtIdWithStrictSignatureAndFlagCheck() throws Exception {
        YouzanShopConfig savedBeforeSync = new YouzanShopConfig(1L, 2L, "123", "测试店", "client", "secret-cipher",
                null, null, null, "UNVERIFIED", false, null, null, null, 0L);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService events = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(configs.findShopConfigByClientAndShop("client", "123")).thenReturn(Optional.of(savedBeforeSync));
        when(configs.findShopConfigByClientAndShop("client", "88888888")).thenReturn(Optional.empty());
        when(configs.findShopConfigsByClientId("client")).thenReturn(List.of(savedBeforeSync));
        when(cipher.decrypt("secret-cipher")).thenReturn("secret");

        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, mock(YouzanRepository.class),
                new YouzanOfficialMessageParser(new ObjectMapper()), mock(RealYouzanAdapter.class), events, cipher,
                scheduler, runtimeConfig());

        String rawSameShop = "{\"kdt_id\":123,\"test\":true,\"msg\":\"ping\"}";
        YouzanEventResponse ackSameShop = service.receive("client", sign("client" + rawSameShop + "secret"),
                "trade_TradePaid", rawSameShop);
        assertThat(ackSameShop.processStatus()).isEqualTo("IGNORED");

        String rawPlaceholderShop = "{\"kdt_id\":88888888,\"test\":true,\"msg\":\"console test\"}";
        YouzanEventResponse ackPlaceholder = service.receive("client", sign("client" + rawPlaceholderShop + "secret"),
                "test_notify", rawPlaceholderShop);
        assertThat(ackPlaceholder.processStatus()).isEqualTo("IGNORED");

        String rawNoShop = "{\"test\":true,\"msg\":\"console test\"}";
        YouzanEventResponse ackNoShop = service.receive("client", sign("client" + rawNoShop + "secret"),
                "test_notify", rawNoShop);
        assertThat(ackNoShop.processStatus()).isEqualTo("IGNORED");

        assertThatThrownBy(() -> service.receive("client", "forged-sign", "test_notify", rawNoShop))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("签名无效")
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        // Formal business push still requires syncEnabled=true
        assertThatThrownBy(() -> service.receive("client", sign("client" + RAW + "secret"), "trade_TradePaid", RAW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未授权或已停用")
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(events, never()).ingest(any(Long.class), any(), any(), any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void refundWebhookEventWithOrderNoIsIgnoredWithoutSchedulingOrderFetch() throws Exception {
        String raw = "{\"msgId\":\"m-refund-1\",\"kdtId\":\"123\",\"msg\":{\"tid\":\"E-REFUND-1\",\"refund_id\":\"R-1\"}}";
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService events = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(configs.findShopConfigByClientAndShop("client", "123")).thenReturn(Optional.of(config()));
        when(cipher.decrypt("secret-cipher")).thenReturn("secret");
        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, mock(YouzanRepository.class),
                new YouzanOfficialMessageParser(new ObjectMapper()), mock(RealYouzanAdapter.class), events, cipher,
                scheduler, runtimeConfig());

        YouzanEventResponse response = service.receive(
                "client", sign("client" + raw + "secret"), "trade_refund_RefundSellerAgree", raw);

        assertThat(response.processStatus()).isEqualTo("IGNORED");
        verify(events, never()).ingest(any(Long.class), any(), any(), any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void uppercaseMd5SignatureVerifiesCleanly() throws Exception {
        String upperSign = sign("client" + RAW + "secret").toUpperCase(Locale.ROOT);
        assertThat(YouzanOfficialSignature.valid("client", "secret", RAW, upperSign)).isTrue();
        assertThat(YouzanOfficialSignature.valid("client", "secret", RAW, "  " + upperSign + "  ")).isTrue();
        assertThat(YouzanOfficialSignature.valid("client", "secret", RAW, "   ")).isFalse();
        assertThat(YouzanOfficialSignature.valid("client", "secret", RAW, null)).isFalse();

        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService events = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(configs.findShopConfigByClientAndShop("client", "123")).thenReturn(Optional.of(config()));
        when(cipher.decrypt("secret-cipher")).thenReturn("secret");
        YouzanEventResponse accepted = new YouzanEventResponse(12L, "m-1", "trade_TradePaid", "E-1",
                "RECEIVED", 0, null, null, Instant.now(), null, false);
        when(events.ingest(eq(2L), any(), eq("OFFICIAL_WEBHOOK"), eq(RAW))).thenReturn(accepted);
        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, mock(YouzanRepository.class),
                new YouzanOfficialMessageParser(new ObjectMapper()), mock(RealYouzanAdapter.class), events, cipher,
                scheduler, runtimeConfig());

        YouzanEventResponse result = service.receive("client", upperSign, "trade_TradePaid", RAW);

        assertThat(result.id()).isEqualTo(12L);
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void duplicateManualFailureEventIsNotRescheduled() throws Exception {
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService events = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(configs.findShopConfigByClientAndShop("client", "123")).thenReturn(Optional.of(config()));
        when(cipher.decrypt("secret-cipher")).thenReturn("secret");
        YouzanEventResponse failed = new YouzanEventResponse(13L, "m-1", "trade_TradePaid", "E-1",
                "MAPPING_FAILED", 1, "SKU未映射", null, Instant.now(), Instant.now(), true);
        when(events.ingest(eq(2L), any(), eq("OFFICIAL_WEBHOOK"), eq(RAW))).thenReturn(failed);
        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, mock(YouzanRepository.class),
                new YouzanOfficialMessageParser(new ObjectMapper()), mock(RealYouzanAdapter.class), events, cipher,
                scheduler, runtimeConfig());

        YouzanEventResponse result = service.receive("client", sign("client" + RAW + "secret"), "trade_TradePaid", RAW);

        assertThat(result.processStatus()).isEqualTo("MAPPING_FAILED");
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void refreshAndProcessUsesExternalOrderNoWhenProviderPayloadIsTradeDetailJson() {
        YouzanRepository eventRepo = mock(YouzanRepository.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        YouzanEventService eventService = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);

        YouzanEvent stored = new YouzanEvent(21L, "123", "m-retry-1", "ORDER_PAID", "E-RETRY-1",
                "{}", "payload-cipher", true, "OFFICIAL_WEBHOOK", "RETRY", 2, "temp error",
                Instant.now(), Instant.now(), null, null, "{}", "trade-detail-cipher", 1L);
        String tradeDetailJson = "{\"success\":true,\"code\":200,\"data\":{\"full_order_info\":{\"order_info\":{\"tid\":\"E-RETRY-1\"}}}}";
        when(eventRepo.findEvent(2L, 21L)).thenReturn(Optional.of(stored));
        when(cipher.decrypt("trade-detail-cipher")).thenReturn(tradeDetailJson);

        YouzanOrderEvent detailEvent = new YouzanOrderEvent("m-retry-1", "ORDER_PAID", "123", "E-RETRY-1",
                "yz-open-1", "T-1", "PAID", 1000L, Instant.now(), Instant.now(),
                "REGULAR", null, null, "PLATFORM", null, List.of());
        YouzanOrderEnvelope envelope = new YouzanOrderEnvelope(detailEvent, tradeDetailJson);
        when(adapter.fetchOrder(2L, "123", "m-retry-1", "E-RETRY-1")).thenReturn(envelope);
        when(eventService.replacePayloadAndProcess(2L, 21L, detailEvent, tradeDetailJson))
                .thenReturn(new YouzanEventResponse(21L, "m-retry-1", "ORDER_PAID", "E-RETRY-1",
                        "PROCESSED", 3, null, 88L, Instant.now(), Instant.now(), false));

        YouzanOfficialMessageService service = new YouzanOfficialMessageService(mock(YouzanConfigRepository.class),
                eventRepo, new YouzanOfficialMessageParser(new ObjectMapper()), adapter, eventService, cipher,
                scheduler, runtimeConfig());

        service.refreshAndProcess(2L, 21L);

        verify(adapter).fetchOrder(2L, "123", "m-retry-1", "E-RETRY-1");
        verify(eventService).replacePayloadAndProcess(2L, 21L, detailEvent, tradeDetailJson);
        verify(eventService, never()).retryLater(any(Long.class), any(Long.class), any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void refreshAndProcessHandlesFullOrderInfoTidLayoutAndRejectsDeepDecodedMsg() {
        YouzanOfficialMessageParser parser = new YouzanOfficialMessageParser(new ObjectMapper());
        String encodedFullOrderMsg = URLEncoder.encode(
                "{\"full_order_info\":{\"order_info\":{\"tid\":\"E-FULL-99\"}}}", StandardCharsets.UTF_8);
        String webhookRaw = "{\"id\":\"m-full\",\"kdt_id\":123,\"msg\":\"" + encodedFullOrderMsg + "\"}";

        YouzanMessage parsed = parser.parse(webhookRaw, "trade_TradePaid");
        assertThat(parsed.orderNo()).isEqualTo("E-FULL-99");

        YouzanMessage rootTidParsed = parser.parse("{\"id\":\"m-root\",\"kdt_id\":123,\"tid\":\"E-ROOT-1\"}", "trade_TradePaid");
        assertThat(rootTidParsed.orderNo()).isEqualTo("E-ROOT-1");

        StringBuilder deepMsg = new StringBuilder();
        for (int i = 0; i < 66; i++) {
            deepMsg.append("{\"a\":");
        }
        deepMsg.append("1").append("}".repeat(66));
        String deepWebhook = "{\"id\":\"m-deep\",\"kdt_id\":123,\"msg\":\""
                + URLEncoder.encode(deepMsg.toString(), StandardCharsets.UTF_8) + "\"}";
        assertThatThrownBy(() -> parser.parse(deepWebhook, "trade_TradePaid"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("64层");

        YouzanRepository eventRepo = mock(YouzanRepository.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        YouzanEventService eventService = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        YouzanEvent stored = new YouzanEvent(22L, "123", "m-full", "trade_TradePaid", null,
                "{}", "payload-cipher", true, "OFFICIAL_WEBHOOK", "RECEIVED", 0, null,
                null, Instant.now(), null, null, "{}", "webhook-cipher", 0L);
        when(eventRepo.findEvent(2L, 22L)).thenReturn(Optional.of(stored));
        when(cipher.decrypt("webhook-cipher")).thenReturn(webhookRaw);
        YouzanOrderEvent detailEvent = new YouzanOrderEvent("m-full", "ORDER_PAID", "123", "E-FULL-99",
                "yz-open-1", "T-2", "PAID", 2000L, Instant.now(), Instant.now(),
                "REGULAR", null, null, "PLATFORM", null, List.of());
        when(adapter.fetchOrder(2L, "123", "m-full", "E-FULL-99"))
                .thenReturn(new YouzanOrderEnvelope(detailEvent, "{}"));

        YouzanOfficialMessageService service = new YouzanOfficialMessageService(mock(YouzanConfigRepository.class),
                eventRepo, parser, adapter, eventService, cipher, mock(TaskScheduler.class), runtimeConfig());

        service.refreshAndProcess(2L, 22L);

        verify(adapter).fetchOrder(2L, "123", "m-full", "E-FULL-99");
        verify(eventService).replacePayloadAndProcess(2L, 22L, detailEvent, "{}");
    }

    @Test
    void retryStopsAutoReschedulingOnceMaxAttemptsReached() {
        YouzanRepository eventRepo = mock(YouzanRepository.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        YouzanEventService eventService = mock(YouzanEventService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);

        YouzanEvent exhausted = new YouzanEvent(31L, "123", "m-max", "trade_TradePaid", "E-MAX",
                "{}", "p", true, "OFFICIAL_WEBHOOK", "RETRY", YouzanOfficialMessageService.MAX_AUTO_RETRIES,
                "err", Instant.now(), Instant.now(), null, null, "{}", "pp", 1L);
        when(eventRepo.findEvent(2L, 31L)).thenReturn(Optional.of(exhausted));

        YouzanOfficialMessageService service = new YouzanOfficialMessageService(mock(YouzanConfigRepository.class),
                eventRepo, new YouzanOfficialMessageParser(new ObjectMapper()), adapter, eventService,
                mock(AesGcmCipher.class), scheduler, runtimeConfig());

        service.refreshAndProcess(2L, 31L);

        verify(adapter, never()).fetchOrder(any(Long.class), any(), any(), any());
        verify(eventService, never()).retryLater(any(Long.class), any(Long.class), any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));

        YouzanEvent lastAttempt = new YouzanEvent(32L, "123", "m-last", "trade_TradePaid", "E-LAST",
                "{}", "p", true, "OFFICIAL_WEBHOOK", "RETRY", YouzanOfficialMessageService.MAX_AUTO_RETRIES - 1,
                "err", Instant.now(), Instant.now(), null, null, "{}", "pp", 1L);
        when(eventRepo.findEvent(2L, 32L)).thenReturn(Optional.of(lastAttempt));
        when(adapter.fetchOrder(2L, "123", "m-last", "E-LAST")).thenThrow(new RuntimeException("network timeout"));
        when(eventService.retryLater(eq(2L), eq(32L), any())).thenReturn(
                new YouzanEventResponse(32L, "m-last", "trade_TradePaid", "E-LAST",
                        "RETRY", YouzanOfficialMessageService.MAX_AUTO_RETRIES, "network timeout",
                        null, Instant.now(), null, false));

        service.refreshAndProcess(2L, 32L);

        verify(eventService).retryLater(eq(2L), eq(32L), any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));

        YouzanEvent retryable = new YouzanEvent(33L, "123", "m-retryable", "trade_TradePaid", "E-RETRYABLE",
                "{}", "p", true, "OFFICIAL_WEBHOOK", "RECEIVED", 0,
                null, null, Instant.now(), null, null, "{}", "pp", 0L);
        when(eventRepo.findEvent(2L, 33L)).thenReturn(Optional.of(retryable));
        when(adapter.fetchOrder(2L, "123", "m-retryable", "E-RETRYABLE"))
                .thenThrow(new RuntimeException("temporary timeout"));
        when(eventService.retryLater(eq(2L), eq(33L), any())).thenReturn(
                new YouzanEventResponse(33L, "m-retryable", "trade_TradePaid", "E-RETRYABLE",
                        "RETRY", 1, "temporary timeout", null, Instant.now(), null, false));

        service.refreshAndProcess(2L, 33L);

        verify(eventService).retryLater(eq(2L), eq(33L), any());
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void rawUnencodedJsonMsgWithPlusAndPercentSequencesParsesIntact() {
        YouzanOfficialMessageParser parser = new YouzanOfficialMessageParser(new ObjectMapper());
        String raw = "{\"msgId\":\"m-raw-json\",\"kdtId\":\"123\",\"msg\":\"{\\\"tid\\\":\\\"E-RAW+1\\\",\\\"note\\\":\\\"a+b %22quoted%22\\\"}\"}";

        YouzanMessage parsed = parser.parse(raw, "trade_TradePaid");

        assertThat(parsed.messageId()).isEqualTo("m-raw-json");
        assertThat(parsed.shopId()).isEqualTo("123");
        assertThat(parsed.orderNo()).isEqualTo("E-RAW+1");
        assertThat(parsed.content().path("note").asText()).isEqualTo("a+b %22quoted%22");
    }

    @Test
    void testMessageWithPlainTextMsgParsesCleanlyAndReturnsIgnored() throws Exception {
        String raw = "{\"kdt_id\":123,\"test\":true,\"msg\":\"test\"}";
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService events = mock(YouzanEventService.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(configs.findShopConfigByClientAndShop("client", "123")).thenReturn(Optional.of(config()));
        when(cipher.decrypt("secret-cipher")).thenReturn("secret");
        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, mock(YouzanRepository.class),
                new YouzanOfficialMessageParser(new ObjectMapper()), mock(RealYouzanAdapter.class), events, cipher,
                scheduler, runtimeConfig());

        YouzanEventResponse response = service.receive(
                "client", sign("client" + raw + "secret"), "trade_TradePaid", raw);

        assertThat(response).isNotNull();
        assertThat(response.processStatus()).isEqualTo("IGNORED");
        assertThat(response.eventId()).isEqualTo("TEST");
        assertThat(response.externalOrderNo()).isNull();
        verify(events, never()).ingest(any(Long.class), any(), any(), any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void refreshAndProcessDoesNotRescheduleWhenRetryLaterReturnsProcessed() {
        YouzanRepository eventRepo = mock(YouzanRepository.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        YouzanEventService eventService = mock(YouzanEventService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);

        YouzanEvent racing = new YouzanEvent(34L, "123", "m-race", "trade_TradePaid", "E-RACE",
                "{}", "p", true, "OFFICIAL_WEBHOOK", "RETRY", 1,
                "err", Instant.now(), Instant.now(), null, null, "{}", "pp", 1L);
        when(eventRepo.findEvent(2L, 34L)).thenReturn(Optional.of(racing));
        when(adapter.fetchOrder(2L, "123", "m-race", "E-RACE")).thenThrow(new RuntimeException("stale worker failure"));
        when(eventService.retryLater(eq(2L), eq(34L), any())).thenReturn(
                new YouzanEventResponse(34L, "m-race", "trade_TradePaid", "E-RACE",
                        "PROCESSED", 2, null, 99L, Instant.now(), Instant.now(), false));

        YouzanOfficialMessageService service = new YouzanOfficialMessageService(mock(YouzanConfigRepository.class),
                eventRepo, new YouzanOfficialMessageParser(new ObjectMapper()), adapter, eventService,
                mock(AesGcmCipher.class), scheduler, runtimeConfig());

        service.refreshAndProcess(2L, 34L);

        verify(eventService).retryLater(eq(2L), eq(34L), any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void recoveryScansEnabledShopForDueEventsAndSkipsExhaustedRetries() {
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanRepository events = mock(YouzanRepository.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        YouzanEventService eventService = mock(YouzanEventService.class);
        YouzanEvent exhausted = new YouzanEvent(41L, "123", "m-exhausted", "trade_TradePaid", "E-EX",
                "{}", "p", true, "OFFICIAL_WEBHOOK", "RETRY", YouzanOfficialMessageService.MAX_AUTO_RETRIES,
                "err", Instant.now().minusSeconds(60), Instant.now(), null, null, "{}", "pp", 1L);
        when(configs.findSyncEnabledShopConfigs()).thenReturn(List.of(config()));
        when(events.findPendingEvents(eq(2L), eq("123"), any(Instant.class), eq(100))).thenReturn(List.of(exhausted));
        YouzanOfficialMessageService service = new YouzanOfficialMessageService(configs, events,
                new YouzanOfficialMessageParser(new ObjectMapper()), adapter,
                eventService, mock(AesGcmCipher.class), mock(TaskScheduler.class), runtimeConfig());

        service.recoverPending();

        verify(events).findPendingEvents(eq(2L), eq("123"), any(Instant.class), eq(100));
        verify(events, never()).findEvent(2L, 41L);
        verify(adapter, never()).fetchOrder(any(Long.class), any(), any(), any());
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
