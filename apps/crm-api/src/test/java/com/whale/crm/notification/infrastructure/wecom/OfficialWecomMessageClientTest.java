package com.whale.crm.notification.infrastructure.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.whale.crm.notification.domain.WecomMessageCommand;
import com.whale.crm.notification.domain.WecomSendResult;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialWecomMessageClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void refreshesExpiredTokenOnceAndSendsOnlyMaskedText() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        startServer();
        server.createContext("/cgi-bin/gettoken", exchange -> {
            int call = tokenCalls.incrementAndGet();
            respond(exchange, "{\"errcode\":0,\"access_token\":\"token-" + call + "\",\"expires_in\":7200}");
        });
        server.createContext("/cgi-bin/message/send", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int call = sends.incrementAndGet();
            respond(exchange, call == 1
                    ? "{\"errcode\":42001,\"errmsg\":\"token expired\"}"
                    : "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"official-message-1\"}");
        });
        OfficialWecomMessageClient client = client(1000, 1000);

        WecomSendResult result = client.send(command());

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.SENT);
        assertThat(result.providerMessageId()).isEqualTo("official-message-1");
        assertThat(tokenCalls.get()).isEqualTo(2);
        assertThat(sends.get()).isEqualTo(2);
        assertThat(body.get()).contains("staff-2", "脱敏标题", "已脱敏内容", "1001")
                .doesNotContain("synthetic-secret");
    }

    @Test
    void providerRateLimitIsRetryableAndContainsNoSecret() throws Exception {
        startServer();
        server.createContext("/cgi-bin/gettoken", exchange ->
                respond(exchange, "{\"errcode\":0,\"access_token\":\"token\",\"expires_in\":7200}"));
        server.createContext("/cgi-bin/message/send", exchange ->
                respond(exchange, "{\"errcode\":45009,\"errmsg\":\"rate limited synthetic-secret\"}"));
        OfficialWecomMessageClient client = client(1000, 1000);

        WecomSendResult result = client.send(command());

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.RETRYABLE);
        assertThat(result.errorMasked()).contains("45009").doesNotContain("synthetic-secret");
    }

    @Test
    void responseTimeoutIsUnknownAndMustNotBeReportedAsFailureOrSuccess() throws Exception {
        startServer();
        server.createContext("/cgi-bin/gettoken", exchange ->
                respond(exchange, "{\"errcode\":0,\"access_token\":\"token\",\"expires_in\":7200}"));
        server.createContext("/cgi-bin/message/send", exchange -> {
            try {
                Thread.sleep(300);
                respond(exchange, "{\"errcode\":0,\"msgid\":\"late-message\"}");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        OfficialWecomMessageClient client = client(1000, 50);

        WecomSendResult result = client.send(command());

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.UNKNOWN);
        assertThat(result.providerMessageId()).isNull();
    }

    @Test
    void acknowledgedInvalidRecipientIsPermanentFailure() throws Exception {
        startServer();
        server.createContext("/cgi-bin/gettoken", exchange ->
                respond(exchange, "{\"errcode\":0,\"access_token\":\"token\",\"expires_in\":7200}"));
        server.createContext("/cgi-bin/message/send", exchange ->
                respond(exchange, "{\"errcode\":0,\"errmsg\":\"ok\",\"invaliduser\":\"staff-2\"}"));
        OfficialWecomMessageClient client = client(1000, 1000);

        WecomSendResult result = client.send(command());

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.FAILED);
        assertThat(result.errorMasked()).contains("INVALID_RECIPIENT");
    }

    private OfficialWecomMessageClient client(int connectTimeout, int readTimeout) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        WecomMessageProperties properties = new WecomMessageProperties("OFFICIAL", "ww-test", "synthetic-secret",
                1001L, base, connectTimeout, readTimeout, 60);
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .readTimeout(Duration.ofMillis(readTimeout))
                .callTimeout(Duration.ofMillis(Math.max(connectTimeout, readTimeout) + 1000))
                .build();
        WecomMessageTokenService tokens = new WecomMessageTokenService(properties, mapper, http);
        return new OfficialWecomMessageClient(properties, tokens, mapper, http);
    }

    private WecomMessageCommand command() {
        return new WecomMessageCommand(1L, 2L, "staff-2", "脱敏标题", "已脱敏内容");
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
