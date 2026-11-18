package com.whale.crm.integration.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.adapter.WecomTokenService;
import com.whale.crm.integration.wecom.sidebar.adapter.OfficialWecomSidebarClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
}
