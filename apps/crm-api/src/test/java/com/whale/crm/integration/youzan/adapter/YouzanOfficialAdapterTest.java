package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YouzanOfficialAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void officialSignatureUsesRawEntity() throws Exception {
        String raw = "{\"msgId\":\"m-1\"}";
        String sign = md5("client" + raw + "secret");
        assertThat(YouzanOfficialSignature.valid("client", "secret", raw, sign)).isTrue();
        assertThat(YouzanOfficialSignature.valid("client", "secret", raw + " ", sign)).isFalse();
        assertThat(YouzanOfficialSignature.valid(" ", "secret", raw, md5(" " + raw + "secret"))).isFalse();
        assertThat(YouzanOfficialSignature.valid("client", "  ", raw, md5("client" + raw + "  "))).isFalse();
    }

    @Test
    void messageParserDecodesMsgAndReadsStableIds() {
        YouzanOfficialMessageParser parser = new YouzanOfficialMessageParser(objectMapper);
        String msg = URLEncoder.encode("{\"tid\":\"E-1\"}", StandardCharsets.UTF_8);
        YouzanMessage parsed = parser.parse("{\"msgId\":\"m-1\",\"kdtId\":\"123\",\"version\":2,\"msg\":\""
                + msg + "\"}", "trade_TradePaid");
        assertThat(parsed.messageId()).isEqualTo("m-1");
        assertThat(parsed.shopId()).isEqualTo("123");
        assertThat(parsed.orderNo()).isEqualTo("E-1");
        assertThat(parsed.version()).isEqualTo(2);
    }

    @Test
    void orderMapperConvertsYuanToFenAndUsesStableCustomerAndSkuIds() throws Exception {
        JsonNode detail = objectMapper.readTree("""
                {"success":true,"code":200,"data":{"full_order_info":{
                  "order_info":{"tid":"E-1","status":"WAIT_SELLER_SEND_GOODS","update_time":"2026-10-24 11:00:00","pay_time":"2026-10-24 10:59:00"},
                  "pay_info":{"payment":"36.22","transaction":["T-1"],"phase_payments":[]},
                  "buyer_info":{"yz_open_id":"yz-open-1"},
                  "orders":[{"item_id":301,"sku_id":9,"num":2}]
                }}}
                """);
        YouzanOrderEvent event = new YouzanOrderMapper().toOrderEvent("123", "m-1", detail);
        assertThat(event.eventType()).isEqualTo("ORDER_PAID");
        assertThat(event.amountFen()).isEqualTo(3622L);
        assertThat(event.externalCustomerId()).isEqualTo("yz-open-1");
        assertThat(event.items()).containsExactly(new YouzanOrderLine("301:9", 2));
        assertThat(event.externalTradeNo()).isEqualTo("T-1");
    }

    @Test
    void remoteClientExchangesTokenAndCallsOfficialOrderApi() throws Exception {
        AtomicReference<String> tokenRequest = new AtomicReference<>();
        AtomicReference<String> orderRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/token", exchange -> {
            tokenRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"success\":true,\"code\":200,\"data\":{\"access_token\":\"token-1\",\"refresh_token\":\"refresh-1\",\"expires\":1893456000000}}");
        });
        server.createContext("/api/youzan.trades.sold.get/4.0.2", exchange -> {
            orderRequest.set(exchange.getRequestURI().getQuery());
            respond(exchange, "{\"success\":true,\"code\":200,\"data\":{\"total_results\":0,\"full_order_info_list\":[]}}");
        });
        server.start();
        try {
            YouzanRemoteClient client = new YouzanRemoteClient(new OkHttpClient(), objectMapper,
                    "http://127.0.0.1:" + server.getAddress().getPort());
            YouzanTokenResponse token = client.fetchSelfToken("client", "secret", "123");
            JsonNode result = client.callApi(token.accessToken(), "youzan.trades.sold.get", "4.0.2",
                    Map.of("page_no", 1, "page_size", 1));
            assertThat(result.path("success").asBoolean()).isTrue();
            assertThat(tokenRequest.get()).contains("\"authorize_type\":\"silent\"").contains("\"grant_id\":\"123\"");
            assertThat(orderRequest.get()).isEqualTo("access_token=token-1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedOrOverlyNestedMessagesAreRejectedBeforeProcessing() {
        YouzanOfficialMessageParser parser = new YouzanOfficialMessageParser(objectMapper);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse("x".repeat(1_000_001), "trade"))
                .hasMessageContaining("1MB");

        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 66; i++) {
            nested.append("{\"a\":");
        }
        nested.append("1").append("}".repeat(66));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse(nested.toString(), "trade"))
                .hasMessageContaining("64层");
    }

    @Test
    void fetchSelfTokenDefaultsRefreshToFalseAndSupportsExplicitRefreshTrue() throws Exception {
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/token", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"success\":true,\"code\":200,\"data\":{\"access_token\":\"token-1\",\"refresh_token\":\"refresh-1\",\"expires\":1893456000000}}");
        });
        server.start();
        try {
            YouzanRemoteClient client = new YouzanRemoteClient(new OkHttpClient(), objectMapper,
                    "http://127.0.0.1:" + server.getAddress().getPort());
            client.fetchSelfToken("client", "secret", "123");
            assertThat(objectMapper.readTree(lastBody.get()).path("refresh").asBoolean(true)).isFalse();

            client.fetchSelfToken("client", "secret", "123", true);
            assertThat(objectMapper.readTree(lastBody.get()).path("refresh").asBoolean(false)).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteClientMapsGatewayTokenAndRateLimitErrorsAndHttp429() throws Exception {
        AtomicReference<Integer> status = new AtomicReference<>(200);
        AtomicReference<String> retryHeader = new AtomicReference<>(null);
        AtomicReference<String> body = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/youzan.trades.sold.get/4.0.2", exchange -> {
            if (retryHeader.get() != null) {
                exchange.getResponseHeaders().add("Retry-After", retryHeader.get());
            }
            respond(exchange, status.get(), body.get());
        });
        server.start();
        try {
            YouzanRemoteClient client = new YouzanRemoteClient(new OkHttpClient(), objectMapper,
                    "http://127.0.0.1:" + server.getAddress().getPort());

            for (String payload : new String[]{
                    "{\"gw_err_resp\":{\"err_code\":4202,\"err_msg\":\"请求凭证过期\"}}",
                    "{\"gw_err_resp\":{\"err_code\":4203,\"err_msg\":\"请求凭证不存在\"}}",
                    "{\"gw_err_resp\":{\"err_code\":40010,\"err_msg\":\"invalid access token\"}}",
                    "{\"gw_err_resp\":{\"err_code\":99001,\"err_msg\":\"请求凭证不存在\"}}",
                    "{\"success\":false,\"code\":4202,\"message\":\"请求凭证过期\"}",
                    "{\"success\":false,\"code\":99002,\"message\":\"请求凭证不存在\"}"
            }) {
                status.set(200);
                retryHeader.set(null);
                body.set(payload);
                assertThatThrownBy(() -> client.callApi("secret-token", "youzan.trades.sold.get", "4.0.2", Map.of()))
                        .isInstanceOf(YouzanApiException.class)
                        .satisfies(ex -> {
                            YouzanApiException apiEx = (YouzanApiException) ex;
                            assertThat(apiEx.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                            assertThat(apiEx.authError()).isTrue();
                            assertThat(apiEx.rateLimited()).isFalse();
                            assertThat(apiEx.getMessage()).doesNotContain("secret-token");
                        });
            }

            for (String payload : new String[]{
                    "{\"gw_err_resp\":{\"err_code\":4101,\"err_msg\":\"调用超限\"}}",
                    "{\"gw_err_resp\":{\"err_code\":40009,\"err_msg\":\"frequency limit\"}}",
                    "{\"gw_err_resp\":{\"err_code\":99003,\"err_msg\":\"系统限流中\"}}"
            }) {
                status.set(200);
                retryHeader.set(null);
                body.set(payload);
                assertThatThrownBy(() -> client.callApi("secret-token", "youzan.trades.sold.get", "4.0.2", Map.of()))
                        .isInstanceOf(YouzanApiException.class)
                        .satisfies(ex -> {
                            YouzanApiException apiEx = (YouzanApiException) ex;
                            assertThat(apiEx.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                            assertThat(apiEx.rateLimited()).isTrue();
                            assertThat(apiEx.authError()).isFalse();
                        });
            }

            status.set(429);
            retryHeader.set("5");
            body.set("{}");
            assertThatThrownBy(() -> client.callApi("secret-token", "youzan.trades.sold.get", "4.0.2", Map.of()))
                    .isInstanceOf(YouzanApiException.class)
                    .satisfies(ex -> {
                        YouzanApiException apiEx = (YouzanApiException) ex;
                        assertThat(apiEx.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                        assertThat(apiEx.rateLimited()).isTrue();
                        assertThat(apiEx.retryAfter()).isEqualTo(Duration.ofSeconds(5));
                    });

            status.set(200);
            retryHeader.set(null);
            body.set("{\"gw_err_resp\":{\"err_code\":50001,\"err_msg\":\"internal secret-leak\"}}");
            assertThatThrownBy(() -> client.callApi("secret-token", "youzan.trades.sold.get", "4.0.2", Map.of()))
                    .isInstanceOf(BusinessException.class)
                    .isNotInstanceOf(YouzanApiException.class)
                    .hasMessage("有赞网关返回错误: 50001")
                    .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("secret-leak"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void orderMapperFallsBackToPayInfoPayTimeValidatesDatesAndChecksCompleteDetail() throws Exception {
        YouzanOrderMapper mapper = new YouzanOrderMapper();
        JsonNode payInfoTimeOrder = objectMapper.readTree("""
                {"order_info":{"tid":"E-PAY-1","status":"TRADE_PAID","update_time":"2026-10-24 11:00:00"},
                 "pay_info":{"payment":"18.50","pay_time":"2026-10-24 10:59:00","transaction":["T-PAY-1"],"phase_payments":[]},
                 "remark_info":{"trade_memo":"账号：测试管家"},
                 "buyer_info":{"yz_open_id":"yz-open-2"},
                 "orders":[{"item_id":301,"sku_id":9,"num":1}]}
                """);
        assertThat(mapper.hasCompleteDetail(payInfoTimeOrder)).isTrue();
        YouzanOrderEvent event = mapper.toOrderEventFromFullOrderInfo("123", null, payInfoTimeOrder);
        assertThat(event.paidAt()).isEqualTo(Instant.parse("2026-10-24T02:59:00Z"));
        assertThat(event.amountFen()).isEqualTo(1850L);
        assertThat(event.externalTradeNo()).isEqualTo("T-PAY-1");

        JsonNode pendingComplete = objectMapper.readTree("""
                {"order_info":{"tid":"E-PEND-1","status":"WAIT_BUYER_PAY","update_time":"2026-10-24 11:00:00"},
                 "pay_info":{"payment":"18.50"},
                 "remark_info":{"trade_memo":""},
                 "buyer_info":{"yz_open_id":"yz-pend-1"},
                 "orders":[{"item_id":301,"sku_id":0,"num":1}]}
                """);
        assertThat(mapper.hasCompleteDetail(pendingComplete)).isTrue();

        JsonNode missingRemarkInfo = objectMapper.readTree("""
                {"order_info":{"tid":"E-PEND-NO-REMARK","status":"WAIT_BUYER_PAY","update_time":"2026-10-24 11:00:00"},
                 "pay_info":{"payment":"18.50"},
                 "buyer_info":{"yz_open_id":"yz-pend-1"},
                 "orders":[{"item_id":301,"sku_id":0,"num":1}]}
                """);
        assertThat(mapper.hasCompleteDetail(missingRemarkInfo)).isFalse();

        JsonNode pendingMissingBuyer = objectMapper.readTree("""
                {"order_info":{"tid":"E-PEND-2","status":"WAIT_BUYER_PAY","update_time":"2026-10-24 11:00:00"},
                 "pay_info":{"payment":"18.50"},
                 "orders":[{"item_id":301,"sku_id":0,"num":1}]}
                """);
        assertThat(mapper.hasCompleteDetail(pendingMissingBuyer)).isFalse();

        JsonNode pendingMissingPayment = objectMapper.readTree("""
                {"order_info":{"tid":"E-PEND-3","status":"WAIT_BUYER_PAY","update_time":"2026-10-24 11:00:00"},
                 "buyer_info":{"yz_open_id":"yz-pend-1"},
                 "orders":[{"item_id":301,"sku_id":0,"num":1}]}
                """);
        assertThat(mapper.hasCompleteDetail(pendingMissingPayment)).isFalse();

        JsonNode pendingMalformedPayment = objectMapper.readTree("""
                {"order_info":{"tid":"E-PEND-4","status":"WAIT_BUYER_PAY","update_time":"2026-10-24 11:00:00"},
                 "pay_info":{"payment":"abc"},
                 "buyer_info":{"yz_open_id":"yz-pend-1"},
                 "orders":[{"item_id":301,"sku_id":0,"num":1}]}
                """);
        assertThat(mapper.hasCompleteDetail(pendingMalformedPayment)).isFalse();

        JsonNode pendingInvalidPayTime = objectMapper.readTree("""
                {"order_info":{"tid":"E-PEND-5","status":"WAIT_BUYER_PAY","update_time":"2026-10-24 11:00:00"},
                 "pay_info":{"payment":"18.50","pay_time":"not-a-date"},
                 "buyer_info":{"yz_open_id":"yz-pend-1"},
                 "orders":[{"item_id":301,"sku_id":0,"num":1}]}
                """);
        assertThat(mapper.hasCompleteDetail(pendingInvalidPayTime)).isFalse();

        JsonNode missingTransaction = objectMapper.readTree("""
                {"order_info":{"tid":"E-MISS-1","status":"TRADE_SUCCESS","update_time":"2026-10-24 11:00:00","pay_time":"2026-10-24 10:59:00"},
                 "pay_info":{"payment":"18.50","transaction":[],"phase_payments":[]},
                 "buyer_info":{"yz_open_id":"yz-open-2"},
                 "orders":[{"item_id":301,"sku_id":9,"num":1}]}
                """);
        assertThat(mapper.hasCompleteDetail(missingTransaction)).isFalse();

        JsonNode invalidUpdateTime = objectMapper.readTree("""
                {"order_info":{"tid":"E-BAD-1","status":"WAIT_BUYER_PAY","update_time":"2026/10/24 11:00:00"},
                 "orders":[{"item_id":301,"sku_id":0,"num":1}]}
                """);
        assertThat(mapper.hasCompleteDetail(invalidUpdateTime)).isFalse();
        assertThatThrownBy(() -> mapper.toOrderEventFromFullOrderInfo("123", "m-bad-1", invalidUpdateTime))
                .isInstanceOf(BusinessException.class)
                .hasMessage("有赞订单时间格式无效: update_time");

        JsonNode invalidPayTime = objectMapper.readTree("""
                {"order_info":{"tid":"E-BAD-2","status":"TRADE_SUCCESS","update_time":"2026-10-24 11:00:00"},
                 "pay_info":{"payment":"10.00","pay_time":"invalid-time","transaction":["T-1"]},
                 "buyer_info":{"yz_open_id":"yz-1"},
                 "orders":[{"item_id":301,"sku_id":0,"num":1}]}
                """);
        assertThat(mapper.hasCompleteDetail(invalidPayTime)).isFalse();
        assertThatThrownBy(() -> mapper.toOrderEventFromFullOrderInfo("123", "m-bad-2", invalidPayTime))
                .isInstanceOf(BusinessException.class)
                .hasMessage("有赞订单时间格式无效: pay_time");

        assertThatThrownBy(() -> YouzanTime.format(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("有赞时间参数不能为空");
    }

    @Test
    void orderMapperMapsClosedStatusesToOrderClosedAndParserAllowsMissingKdtIdOnlyForTestMessages() throws Exception {
        YouzanOrderMapper mapper = new YouzanOrderMapper();
        for (String closedStatus : new String[]{"TRADE_CLOSED", "TRADE_CLOSED_BY_USER"}) {
            JsonNode closedOrder = objectMapper.readTree("""
                    {"order_info":{"tid":"E-CLOSED-1","status":"%s","update_time":"2026-10-24 11:00:00"},
                     "pay_info":{"payment":"36.22"},
                     "remark_info":{"trade_memo":""},
                     "buyer_info":{"yz_open_id":"yz-closed-1"},
                     "orders":[{"item_id":301,"sku_id":9,"num":1}]}
                    """.formatted(closedStatus));
            assertThat(mapper.hasCompleteDetail(closedOrder)).isTrue();
            YouzanOrderEvent event = mapper.toOrderEventFromFullOrderInfo("123", "m-closed", closedOrder);
            assertThat(event.eventType()).isEqualTo("ORDER_CLOSED");
            assertThat(event.status()).isEqualTo(closedStatus);
        }

        YouzanOfficialMessageParser parser = new YouzanOfficialMessageParser(objectMapper);
        YouzanMessage testWithoutShop = parser.parse("{\"test\":true,\"msg\":\"ping\"}", "test_notify");
        assertThat(testWithoutShop.test()).isTrue();
        assertThat(testWithoutShop.messageId()).isEqualTo("TEST");
        assertThat(testWithoutShop.shopId()).isNull();

        assertThatThrownBy(() -> parser.parse("{\"msgId\":\"m-no-shop\",\"msg\":{\"tid\":\"E-1\"}}", "trade_TradePaid"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("kdtId");
    }

    @Test
    void routerReturnsClearShopNotReadyErrorWhenRealEnabledButShopNotConfiguredOrDisabled() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MockYouzanAdapter> mocks = mock(ObjectProvider.class);
        MockYouzanAdapter mockAdapter = mock(MockYouzanAdapter.class);
        when(mocks.getIfAvailable()).thenReturn(mockAdapter);
        DisabledYouzanAdapter disabled = new DisabledYouzanAdapter();
        RealYouzanAdapter real = mock(RealYouzanAdapter.class);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        IntegrationRuntimeConfigService runtime = mock(IntegrationRuntimeConfigService.class);
        when(runtime.youzanRealEnabled(2L)).thenReturn(true);
        when(runtime.youzanRealEnabled(1L)).thenReturn(false);
        when(configs.findShopConfig(2L, "missing-shop")).thenReturn(Optional.empty());
        when(configs.findShopConfig(2L, "disabled-shop")).thenReturn(Optional.of(new YouzanShopConfig(
                1L, 2L, "disabled-shop", "停用店", "client", "secret",
                null, null, null, "CONNECTED", false, null, null, null, 0L)));

        YouzanAdapterRouter router = new YouzanAdapterRouter(mocks, disabled, real, configs, runtime);

        YouzanAdapter missingShopAdapter = router.adapter(2L, "missing-shop");
        assertThat(missingShopAdapter.name()).isEqualTo("YOUZAN_SHOP_SYNC_DISABLED");
        assertThatThrownBy(() -> missingShopAdapter.pullOrders(2L, "missing-shop", null, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该有赞店铺未配置或未启用同步，请先在店铺配置中完成授权测试并启用同步")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("FEATURE_REAL_YOUZAN"));

        YouzanAdapter disabledShopAdapter = router.adapter(2L, "disabled-shop");
        assertThatThrownBy(() -> disabledShopAdapter.pullOrders(2L, "disabled-shop", null, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该有赞店铺未配置或未启用同步，请先在店铺配置中完成授权测试并启用同步");

        assertThat(router.webhookAdapter(1L)).isSameAs(mockAdapter);
        when(runtime.youzanRealEnabled(1L)).thenReturn(true);
        YouzanAdapter officialOnlyAdapter = router.webhookAdapter(1L);
        assertThat(officialOnlyAdapter).isNotSameAs(mockAdapter);
        assertThat(officialOnlyAdapter.name()).isEqualTo("YOUZAN_OFFICIAL_WEBHOOK_ONLY");
        YouzanVerification verification = officialOnlyAdapter.verifyWebhook("", null);
        assertThat(verification.valid()).isFalse();
        assertThat(verification.errorCode()).isEqualTo("OFFICIAL_SIGNATURE_REQUIRED");
        assertThatThrownBy(() -> officialOnlyAdapter.pullOrders(1L, "shop", null, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessage("真实有赞接入已启用，请使用官方消息回调入口");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String md5(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
