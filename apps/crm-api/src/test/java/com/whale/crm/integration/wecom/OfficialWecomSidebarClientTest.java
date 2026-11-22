package com.whale.crm.integration.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.adapter.WecomTokenService;
import com.whale.crm.integration.wecom.sidebar.adapter.OfficialWecomSidebarClient;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarClient;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficialWecomSidebarClientTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void usesDocumentedOAuthAndTicketEndpoints() throws Exception {
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery());
            String body = exchange.getRequestURI().getPath().contains("auth/getuserinfo")
                    ? "{\"errcode\":0,\"userid\":\"staff-2\"}"
                    : "{\"errcode\":0,\"ticket\":\"ticket-value\",\"expires_in\":7200}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        WecomOfficialProperties properties = new WecomOfficialProperties(true, "corp", "secret", base,
                "", "", 3000, 3000, 100, 10);
        WecomTokenService tokens = mock(WecomTokenService.class);
        when(tokens.token()).thenReturn("access-token");
        OfficialWecomSidebarClient client = new OfficialWecomSidebarClient(properties, tokens, new ObjectMapper());

        assertThat(client.oauthUserid("oauth-code")).isEqualTo("staff-2");
        assertThat(client.ticket(false).ticket()).isEqualTo("ticket-value");
        assertThat(client.ticket(true).ticket()).isEqualTo("ticket-value");

        assertThat(requests).anyMatch(value -> value.startsWith("/cgi-bin/auth/getuserinfo?")
                && value.contains("code=oauth-code") && value.contains("access_token=access-token"));
        assertThat(requests).anyMatch(value -> value.startsWith("/cgi-bin/get_jsapi_ticket?"));
        assertThat(requests).anyMatch(value -> value.startsWith("/cgi-bin/ticket/get?")
                && value.contains("type=agent_config"));
    }

    @Test
    void uploadsTempMediaMultipartAndRetriesExpiredToken() throws Exception {
        AtomicInteger uploadAttempts = new AtomicInteger();
        AtomicReference<String> lastQuery = new AtomicReference<>();
        AtomicReference<String> lastBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/media/upload", exchange -> {
            int attempt = uploadAttempts.incrementAndGet();
            lastQuery.set(exchange.getRequestURI().getQuery());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = attempt == 1
                    ? "{\"errcode\":42001,\"errmsg\":\"access_token expired\"}"
                    : "{\"errcode\":0,\"errmsg\":\"ok\",\"type\":\"image\",\"media_id\":\"MEDIA-IMG-001\",\"created_at\":\"1730000000\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        WecomOfficialProperties properties = new WecomOfficialProperties(true, "corp", "secret", base,
                "", "", 3000, 3000, 100, 10);
        WecomTokenService tokens = mock(WecomTokenService.class);
        when(tokens.token()).thenReturn("expired-token");
        when(tokens.refresh()).thenReturn("fresh-token");
        OfficialWecomSidebarClient client = new OfficialWecomSidebarClient(properties, tokens, new ObjectMapper());

        WecomSidebarClient.WecomUploadedMedia uploaded = client.uploadTempMedia(
                "image", "brochure\"v1.png", "image/png", "PNG-BINARY-CONTENT".getBytes(StandardCharsets.UTF_8));

        assertThat(uploaded.mediaId()).isEqualTo("MEDIA-IMG-001");
        assertThat(uploaded.type()).isEqualTo("image");
        assertThat(uploaded.createdAt()).isEqualTo(Instant.ofEpochSecond(1730000000L));
        assertThat(uploadAttempts.get()).isEqualTo(2);
        assertThat(lastQuery.get()).contains("access_token=fresh-token").contains("type=image");
        assertThat(lastBody.get()).contains("name=\"media\"").contains("filename=\"brochurev1.png\"")
                .contains("PNG-BINARY-CONTENT");
        verify(tokens, times(1)).refresh();

        assertThatThrownBy(() -> client.uploadTempMedia("video", "demo.mp4", "video/mp4", new byte[16]))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> client.uploadTempMedia("file", "tiny.pdf", "application/pdf", new byte[4]))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
