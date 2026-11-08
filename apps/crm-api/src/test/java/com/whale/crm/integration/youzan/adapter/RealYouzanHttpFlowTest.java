package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.whale.crm.integration.youzan.application.YouzanTokenService;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RealYouzanHttpFlowTest {
    @Test
    void configuredShopFlowsThroughTokenListAndDetailOverHttp() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger listCalls = new AtomicInteger();
        AtomicInteger detailCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/token", exchange -> {
            tokenCalls.incrementAndGet();
            respond(exchange, "{\"success\":true,\"code\":200,\"data\":{\"access_token\":\"http-token\",\"refresh_token\":\"http-refresh\",\"expires\":1893456000000}}");
        });
        server.createContext("/api/youzan.trades.sold.get/4.0.2", exchange -> {
            listCalls.incrementAndGet();
            respond(exchange, "{\"success\":true,\"code\":200,\"data\":{\"total_results\":1,\"full_order_info_list\":[{\"full_order_info\":{\"order_info\":{\"tid\":\"E-HTTP-1\"}}}]}}");
        });
        server.createContext("/api/youzan.trade.get/4.0.2", exchange -> {
            detailCalls.incrementAndGet();
            respond(exchange, "{\"success\":true,\"code\":200,\"data\":{\"full_order_info\":{"
                    + "\"order_info\":{\"tid\":\"E-HTTP-1\",\"status\":\"TRADE_SUCCESS\",\"update_time\":\"2026-10-24 11:00:00\",\"pay_time\":\"2026-10-24 10:59:00\"},"
                    + "\"pay_info\":{\"payment\":\"36.22\",\"transaction\":[\"T-HTTP-1\"],\"phase_payments\":[]},"
                    + "\"buyer_info\":{\"yz_open_id\":\"yz-http-1\"},\"orders\":[{\"item_id\":301,\"sku_id\":9,\"num\":2}]}}}");
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            YouzanRemoteClient remote = new YouzanRemoteClient(new okhttp3.OkHttpClient(), mapper, base);
            YouzanConfigRepository repository = mock(YouzanConfigRepository.class);
            AesGcmCipher cipher = mock(AesGcmCipher.class);
            java.util.concurrent.atomic.AtomicReference<YouzanShopConfig> config = new java.util.concurrent.atomic.AtomicReference<>(
                    new YouzanShopConfig(1L, 2L, "123", "HTTP测试店", "client", "secret-cipher",
                            null, null, null, "UNVERIFIED", true, null, null, null, 0L));
            when(repository.findShopConfig(2L, "123")).thenAnswer(invocation -> Optional.of(config.get()));
            when(repository.findShopConfigForUpdate(2L, "123")).thenAnswer(invocation -> Optional.of(config.get()));
            when(repository.updateShopTokens(eq(2L), eq(1L), eq(0L), any())).thenAnswer(invocation -> {
                config.set(invocation.getArgument(3));
                return 1;
            });
            when(cipher.decrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
            when(cipher.encrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
            YouzanTokenService tokens = new YouzanTokenService(repository, remote, cipher, transactionManager());
            RealYouzanAdapter adapter = new RealYouzanAdapter(tokens, remote, new YouzanOrderMapper(), mapper, "4.0.2");
            Instant start = Instant.parse("2026-10-01T00:00:00Z");
            Instant end = Instant.parse("2026-10-26T00:00:00Z");
            String cursor = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", start, end, end, 1));

            YouzanPullPage page = adapter.pullOrders(2L, "123", cursor, 50);

            assertThat(page.events()).hasSize(1);
            assertThat(page.events().get(0).amountFen()).isEqualTo(3622L);
            assertThat(page.events().get(0).externalTradeNo()).isEqualTo("T-HTTP-1");
            assertThat(tokenCalls.get()).isEqualTo(1);
            assertThat(listCalls.get()).isEqualTo(1);
            assertThat(detailCalls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            public void commit(TransactionStatus status) {
            }

            public void rollback(TransactionStatus status) {
            }
        };
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
