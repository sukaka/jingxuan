package com.whale.crm.integration.wecom.tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.adapter.WecomTokenService;
import com.whale.crm.integration.wecom.tag.adapter.OfficialWecomTagClient;
import com.whale.crm.integration.wecom.tag.adapter.WecomCorpTag;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficialWecomTagClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void callsOfficialTagEndpointsAndAutoRefreshesExpiredTokenOnce() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger listCalls = new AtomicInteger();
        AtomicReference<String> addBody = new AtomicReference<>();
        AtomicReference<String> editBody = new AtomicReference<>();
        AtomicReference<String> delBody = new AtomicReference<>();
        AtomicReference<String> markBody = new AtomicReference<>();

        server.createContext("/cgi-bin/gettoken", exchange -> {
            int n = tokenCalls.incrementAndGet();
            respond(exchange, "{\"errcode\":0,\"access_token\":\"tok-" + n + "\",\"expires_in\":7200}");
        });
        server.createContext("/cgi-bin/externalcontact/get_corp_tag_list", exchange -> {
            int n = listCalls.incrementAndGet();
            if (n == 1) {
                respond(exchange, "{\"errcode\":42001,\"errmsg\":\"access_token expired\"}");
                return;
            }
            respond(exchange, "{\"errcode\":0,\"tag_group\":["
                    + "{\"group_id\":\"grp-1\",\"group_name\":\"VIP组\",\"deleted\":false,\"tag\":["
                    + "{\"id\":\"et-1\",\"name\":\"核心贵宾\",\"order\":10,\"deleted\":false},"
                    + "{\"id\":\"et-del\",\"name\":\"已删标签\",\"order\":20,\"deleted\":true}"
                    + "]},"
                    + "{\"group_id\":\"grp-del\",\"group_name\":\"已删组\",\"deleted\":true,\"tag\":["
                    + "{\"id\":\"et-2\",\"name\":\"不应出现\",\"order\":1,\"deleted\":false}"
                    + "]}"
                    + "]}");
        });
        server.createContext("/cgi-bin/externalcontact/add_corp_tag", exchange -> {
            addBody.set(readBody(exchange));
            respond(exchange, "{\"errcode\":0,\"tag_group\":{\"group_id\":\"grp-default\","
                    + "\"group_name\":\"鲸选CRM标签\",\"tag\":[{\"id\":\"et-new\",\"name\":\"敏感肌\",\"order\":5}]}}");
        });
        server.createContext("/cgi-bin/externalcontact/edit_corp_tag", exchange -> {
            editBody.set(readBody(exchange));
            respond(exchange, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        });
        server.createContext("/cgi-bin/externalcontact/del_corp_tag", exchange -> {
            delBody.set(readBody(exchange));
            respond(exchange, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        });
        server.createContext("/cgi-bin/externalcontact/mark_tag", exchange -> {
            markBody.set(readBody(exchange));
            respond(exchange, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        });
        server.start();

        OfficialWecomTagClient client = buildClient("http://127.0.0.1:" + server.getAddress().getPort());

        List<WecomCorpTag> tags = client.listCorpTags();
        assertThat(tags).containsExactly(new WecomCorpTag("grp-1", "VIP组", "et-1", "核心贵宾", 10));
        assertThat(tokenCalls.get()).isEqualTo(2);
        assertThat(listCalls.get()).isEqualTo(2);

        WecomCorpTag created = client.addCorpTag(null, null, "敏感肌", 5);
        assertThat(created).isEqualTo(new WecomCorpTag("grp-default", "鲸选CRM标签", "et-new", "敏感肌", 5));
        assertThat(addBody.get()).contains("\"group_name\":\"鲸选CRM标签\"").contains("\"name\":\"敏感肌\"");

        client.editCorpTag("et-new", "极敏感肌", 6);
        assertThat(editBody.get()).contains("\"id\":\"et-new\"").contains("\"name\":\"极敏感肌\"");

        client.deleteCorpTag("et-new");
        assertThat(delBody.get()).contains("\"tag_id\":[\"et-new\"]");

        client.markCustomerTags("staff-1", "wm-ext-1", List.of("et-1"), List.of("et-old"));
        assertThat(markBody.get()).contains("\"userid\":\"staff-1\"")
                .contains("\"external_userid\":\"wm-ext-1\"")
                .contains("\"add_tag\":[\"et-1\"]")
                .contains("\"remove_tag\":[\"et-old\"]");
    }

    @Test
    void masksUpstreamErrorsAndMapsRateLimitWithoutLeakingSecrets() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange ->
                respond(exchange, "{\"errcode\":0,\"access_token\":\"top-secret-token-xyz\",\"expires_in\":7200}"));
        server.createContext("/cgi-bin/externalcontact/get_corp_tag_list", exchange ->
                respond(exchange, "{\"errcode\":45009,\"errmsg\":\"secret-leak-payload\"}"));
        server.createContext("/cgi-bin/externalcontact/edit_corp_tag", exchange ->
                respond(exchange, "{\"errcode\":40068,\"errmsg\":\"invalid tagid raw-upstream\"}"));
        server.start();

        OfficialWecomTagClient client = buildClient("http://127.0.0.1:" + server.getAddress().getPort());

        assertThatThrownBy(client::listCorpTags)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(be.getMessage()).contains("45009")
                            .doesNotContain("top-secret-token-xyz")
                            .doesNotContain("secret-leak-payload");
                });

        assertThatThrownBy(() -> client.editCorpTag("bad-id", "测试", 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(be.getMessage()).contains("40068")
                            .doesNotContain("top-secret-token-xyz")
                            .doesNotContain("raw-upstream");
                });
    }

    private OfficialWecomTagClient buildClient(String baseUrl) {
        WecomOfficialProperties properties = new WecomOfficialProperties(1L, true, "ww-tag-test", "secret-val",
                baseUrl, "token", "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG", 1000, 1000, 100, 10);
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
        WecomTokenService tokenService = new WecomTokenService(properties, mapper, http, redis);
        return new OfficialWecomTagClient(properties, tokenService, mapper, http);
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
