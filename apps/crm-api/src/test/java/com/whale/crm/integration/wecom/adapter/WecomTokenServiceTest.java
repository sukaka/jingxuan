package com.whale.crm.integration.wecom.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomTokenServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returnsCachedTokenFromRedisWithoutCallingHttp() {
        when(valueOps.get("wecom:access_token:ww-corp-1")).thenReturn("cached-token");
        WecomOfficialProperties props = new WecomOfficialProperties(true, "ww-corp-1", "secret",
                "http://127.0.0.1:1", "", "", 1000, 1000, 100, 10);
        WecomTokenService service = new WecomTokenService(props, objectMapper, new OkHttpClient(), redis);

        assertThat(service.token()).isEqualTo("cached-token");
        verify(valueOps, times(1)).get("wecom:access_token:ww-corp-1");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void fetchesTokenOnCacheMissAndStoresWithSkewedOrMinimumTtl() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> responseBody = new AtomicReference<>(
                "{\"errcode\":0,\"access_token\":\"fresh-7200\",\"expires_in\":7200}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        WecomOfficialProperties props = new WecomOfficialProperties(true, "ww-corp-1", "secret",
                baseUrl, "", "", 1000, 1000, 100, 10);
        WecomTokenService service = new WecomTokenService(props, objectMapper, new OkHttpClient(), redis);

        when(valueOps.get("wecom:access_token:ww-corp-1")).thenReturn(null);
        assertThat(service.token()).isEqualTo("fresh-7200");
        assertThat(calls.get()).isEqualTo(1);
        verify(valueOps).set("wecom:access_token:ww-corp-1", "fresh-7200", Duration.ofSeconds(7140));

        // Short expires_in=90 should clamp TTL to minimum 60 seconds
        responseBody.set("{\"errcode\":0,\"access_token\":\"fresh-short\",\"expires_in\":90}");
        assertThat(service.refresh()).isEqualTo("fresh-short");
        assertThat(calls.get()).isEqualTo(2);
        verify(valueOps).set("wecom:access_token:ww-corp-1", "fresh-short", Duration.ofSeconds(60));
    }

    @Test
    void doubleCheckedReadSkipsHttpWhenAnotherCallerPopulatedCache() {
        when(valueOps.get("wecom:access_token:ww-corp-1")).thenReturn(null, "populated-by-peer");
        WecomOfficialProperties props = new WecomOfficialProperties(true, "ww-corp-1", "secret",
                "http://127.0.0.1:1", "", "", 1000, 1000, 100, 10);
        WecomTokenService service = new WecomTokenService(props, objectMapper, new OkHttpClient(), redis);

        assertThat(service.token()).isEqualTo("populated-by-peer");
        verify(valueOps, times(2)).get("wecom:access_token:ww-corp-1");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void throwsCleanBusinessExceptionWhenRedisFailsOnReadOrWrite() throws Exception {
        when(valueOps.get("wecom:access_token:ww-corp-1"))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        WecomOfficialProperties props = new WecomOfficialProperties(true, "ww-corp-1", "secret",
                "http://127.0.0.1:1", "", "", 1000, 1000, 100, 10);
        WecomTokenService service = new WecomTokenService(props, objectMapper, new OkHttpClient(), redis);

        assertThatThrownBy(service::token)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.AUTH_PROTECTION_UNAVAILABLE);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            byte[] bytes = "{\"errcode\":0,\"access_token\":\"tok\",\"expires_in\":7200}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        WecomOfficialProperties liveProps = new WecomOfficialProperties(true, "ww-corp-1", "secret",
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "", 1000, 1000, 100, 10);
        WecomTokenService writeFailService = new WecomTokenService(liveProps, objectMapper, new OkHttpClient(), redis);
        doThrow(new DataAccessResourceFailureException("redis write down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(writeFailService::refresh)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.AUTH_PROTECTION_UNAVAILABLE);
    }

    @Test
    void throwsPendingBusinessRuleWhenCorpIdOrSecretBlank() {
        WecomOfficialProperties blankCorp = new WecomOfficialProperties(true, "", "secret",
                "http://127.0.0.1:1", "", "", 1000, 1000, 100, 10);
        WecomTokenService service1 = new WecomTokenService(blankCorp, objectMapper, new OkHttpClient(), redis);
        assertThatThrownBy(service1::token)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.PENDING_BUSINESS_RULE);

        WecomOfficialProperties blankSecret = new WecomOfficialProperties(true, "ww-corp-1", "",
                "http://127.0.0.1:1", "", "", 1000, 1000, 100, 10);
        WecomTokenService service2 = new WecomTokenService(blankSecret, objectMapper, new OkHttpClient(), redis);
        assertThatThrownBy(service2::refresh)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.PENDING_BUSINESS_RULE);
    }
}
