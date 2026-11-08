package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.youzan.application.YouzanTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealYouzanAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void pullUsesTimeWindowAndPageThenConfirmsEachOrderDetail() throws Exception {
        YouzanTokenService tokenService = mock(YouzanTokenService.class);
        YouzanRemoteClient remote = mock(YouzanRemoteClient.class);
        when(tokenService.accessToken(2L, "123")).thenReturn("token");
        JsonNode list = objectMapper.readTree("""
                {"success":true,"code":200,"data":{"total_results":3,"full_order_info_list":[
                  {"full_order_info":{"order_info":{"tid":"E-1"}}}
                ]}}
                """);
        JsonNode detail = objectMapper.readTree("""
                {"success":true,"code":200,"data":{"full_order_info":{
                  "order_info":{"tid":"E-1","status":"TRADE_SUCCESS","update_time":"2026-10-24 11:00:00","pay_time":"2026-10-24 10:59:00"},
                  "pay_info":{"payment":"1.01","transaction":["T-1"],"phase_payments":[]},
                  "buyer_info":{"yz_open_id":"yz-1"},
                  "orders":[{"item_id":301,"sku_id":0,"num":1}]
                }}}
                """);
        when(remote.callApi(eq("token"), eq("youzan.trades.sold.get"), eq("4.0.2"), any())).thenReturn(list);
        when(remote.callApi(eq("token"), eq("youzan.trade.get"), eq("4.0.2"), any())).thenReturn(detail);
        RealYouzanAdapter adapter = new RealYouzanAdapter(tokenService, remote, new YouzanOrderMapper(), objectMapper, "4.0.2");
        Instant start = Instant.parse("2026-10-01T00:00:00Z");
        Instant end = Instant.parse("2026-10-26T00:00:00Z");
        String cursor = objectMapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", start, end, end, 1));

        YouzanPullPage page = adapter.pullOrders(2L, "123", cursor, 1);

        assertThat(page.events()).hasSize(1);
        assertThat(page.events().get(0).amountFen()).isEqualTo(101L);
        assertThat(page.events().get(0).externalCustomerId()).isEqualTo("yz-1");
        assertThat(page.rawPayloads()).containsKey(page.events().get(0).eventId());
        assertThat(page.hasMore()).isTrue();
        assertThat(objectMapper.readTree(page.nextCursor()).path("pageNo").asInt()).isEqualTo(2);
        ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.captor();
        verify(remote).callApi(eq("token"), eq("youzan.trades.sold.get"), eq("4.0.2"), query.capture());
        assertThat(query.getValue()).containsEntry("page_no", 1).containsEntry("page_size", 1)
                .containsKey("start_created").containsKey("end_created");
        verify(remote).callApi(eq("token"), eq("youzan.trade.get"), eq("4.0.2"), eq(Map.of("tid", "E-1")));
    }

    @Test
    void pageOneHundredBeyondOfficialCapForcesWindowSplit() throws Exception {
        YouzanTokenService tokenService = mock(YouzanTokenService.class);
        YouzanRemoteClient remote = mock(YouzanRemoteClient.class);
        when(tokenService.accessToken(2L, "123")).thenReturn("token");
        JsonNode list = objectMapper.readTree("{\"success\":true,\"code\":200,\"data\":{\"total_results\":10001,\"full_order_info_list\":[]}}");
        when(remote.callApi(eq("token"), eq("youzan.trades.sold.get"), eq("4.0.2"), any())).thenReturn(list);
        RealYouzanAdapter adapter = new RealYouzanAdapter(tokenService, remote, new YouzanOrderMapper(), objectMapper, "4.0.2");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-03-01T00:00:00Z");
        String cursor = objectMapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", start, end, end, 100));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.pullOrders(2L, "123", cursor, 100))
                .isInstanceOf(YouzanWindowTooLargeException.class);
    }

    @Test
    void pageOneHundredAtExactCapCanFinishNormally() throws Exception {
        YouzanTokenService tokenService = mock(YouzanTokenService.class);
        YouzanRemoteClient remote = mock(YouzanRemoteClient.class);
        when(tokenService.accessToken(2L, "123")).thenReturn("token");
        JsonNode list = objectMapper.readTree("{\"success\":true,\"code\":200,\"data\":{\"total_results\":10000,\"full_order_info_list\":[]}}");
        when(remote.callApi(eq("token"), eq("youzan.trades.sold.get"), eq("4.0.2"), any())).thenReturn(list);
        RealYouzanAdapter adapter = new RealYouzanAdapter(tokenService, remote, new YouzanOrderMapper(), objectMapper, "4.0.2");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-03-01T00:00:00Z");
        String cursor = objectMapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", start, end, end, 100));

        YouzanPullPage page = adapter.pullOrders(2L, "123", cursor, 100);

        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void tokenFailureRefreshesOnceAndRetriesWithNewToken() throws Exception {
        YouzanTokenService tokenService = mock(YouzanTokenService.class);
        YouzanRemoteClient remote = mock(YouzanRemoteClient.class);
        when(tokenService.accessToken(2L, "123")).thenReturn("old-token");
        when(tokenService.refreshAccessToken(2L, "123")).thenReturn("new-token");
        YouzanApiException expired = new YouzanApiException(com.whale.crm.platform.web.ErrorCode.UNAUTHORIZED,
                "expired", 401, true, false, null);
        when(remote.callApi(eq("old-token"), eq("youzan.trade.get"), eq("4.0.2"), any())).thenThrow(expired);
        when(remote.callApi(eq("new-token"), eq("youzan.trade.get"), eq("4.0.2"), any())).thenReturn(detail());
        RealYouzanAdapter adapter = new RealYouzanAdapter(tokenService, remote, new YouzanOrderMapper(), objectMapper, "4.0.2", 0);

        YouzanOrderEnvelope result = adapter.fetchOrder(2L, "123", "m-1", "E-1");

        assertThat(result.event().externalOrderNo()).isEqualTo("E-1");
        verify(tokenService).refreshAccessToken(2L, "123");
        verify(remote).callApi(eq("new-token"), eq("youzan.trade.get"), eq("4.0.2"), any());
    }

    @Test
    void rateLimitRetriesAreBounded() throws Exception {
        YouzanTokenService tokenService = mock(YouzanTokenService.class);
        YouzanRemoteClient remote = mock(YouzanRemoteClient.class);
        when(tokenService.accessToken(2L, "123")).thenReturn("token");
        YouzanApiException limited = new YouzanApiException(com.whale.crm.platform.web.ErrorCode.RATE_LIMITED,
                "limited", 429, false, true, java.time.Duration.ZERO);
        when(remote.callApi(eq("token"), eq("youzan.trade.get"), eq("4.0.2"), any())).thenThrow(limited);
        RealYouzanAdapter adapter = new RealYouzanAdapter(tokenService, remote, new YouzanOrderMapper(), objectMapper, "4.0.2", 0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.fetchOrder(2L, "123", "m-1", "E-1"))
                .isSameAs(limited);
        verify(remote, org.mockito.Mockito.times(3)).callApi(eq("token"), eq("youzan.trade.get"), eq("4.0.2"), any());
    }

    private JsonNode detail() throws Exception {
        return objectMapper.readTree("""
                {"success":true,"code":200,"data":{"full_order_info":{
                  "order_info":{"tid":"E-1","status":"TRADE_SUCCESS","update_time":"2026-10-24 11:00:00","pay_time":"2026-10-24 10:59:00"},
                  "pay_info":{"payment":"1.01","transaction":["T-1"],"phase_payments":[]},
                  "buyer_info":{"yz_open_id":"yz-1"},"orders":[{"item_id":301,"sku_id":0,"num":1}]
                }}}
                """);
    }
}
