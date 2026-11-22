package com.whale.crm.integration.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.whale.crm.integration.wecom.adapter.OfficialWecomContactClient;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.adapter.WecomTokenService;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficialWecomContactClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void callsOfficialTokenAndBatchEndpointsWithCursorAndLimit() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> tokenQuery = new AtomicReference<>();
        AtomicReference<String> batchBody = new AtomicReference<>();
        AtomicInteger batches = new AtomicInteger();
        server.createContext("/cgi-bin/gettoken", exchange -> {
            tokenQuery.set(exchange.getRequestURI().getQuery());
            respond(exchange, "{\"errcode\":0,\"access_token\":\"test-access-token\",\"expires_in\":7200}");
        });
        server.createContext("/cgi-bin/externalcontact/batch/get_by_user", exchange -> {
            batchBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int call = batches.incrementAndGet();
            String next = call == 1 ? "next-page" : "";
            respond(exchange, "{\"errcode\":0,\"external_contact_list\":[{\"external_contact\":{\"external_userid\":\"wo-test\",\"name\":\"测试客户\",\"type\":1},\"follow_info\":{\"userid\":\"staff-1\",\"createtime\":1792990000,\"tag_id\":[\"tag-1\"]}}],\"next_cursor\":\"" + next + "\"}");
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        WecomOfficialProperties properties = new WecomOfficialProperties(true, "ww-test", "secret-test", base,
                "token", "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG", 1000, 1000, 100, 10);
        OkHttpClient http = new OkHttpClient();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Map<String, String> store = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        WecomTokenService tokens = new WecomTokenService(properties, mapper, http, redis);
        OfficialWecomContactClient client = new OfficialWecomContactClient(properties, tokens, mapper, http);

        var first = client.batchGetByUser(List.of("staff-1"), null, 100);
        var second = client.batchGetByUser(List.of("staff-1"), first.nextCursor(), 100);

        assertThat(first.contacts()).hasSize(1);
        assertThat(first.contacts().getFirst().name()).isEqualTo("测试客户");
        assertThat(second.nextCursor()).isEmpty();
        assertThat(tokenQuery.get()).contains("corpid=ww-test").contains("corpsecret=secret-test");
        assertThat(batchBody.get()).contains("next-page").contains("\"limit\":100").contains("staff-1");
        assertThat(batches.get()).isEqualTo(2);
        assertThat(tokens.token()).isEqualTo("test-access-token");
        verify(values).set("wecom:access_token:ww-test", "test-access-token", Duration.ofSeconds(7140));
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
