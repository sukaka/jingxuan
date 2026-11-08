package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class YouzanOfficialAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void officialSignatureUsesRawEntity() throws Exception {
        String raw = "{\"msgId\":\"m-1\"}";
        String sign = md5("client" + raw + "secret");
        assertThat(YouzanOfficialSignature.valid("client", "secret", raw, sign)).isTrue();
        assertThat(YouzanOfficialSignature.valid("client", "secret", raw + " ", sign)).isFalse();
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

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String md5(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
