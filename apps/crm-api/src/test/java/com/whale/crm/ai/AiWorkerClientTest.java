package com.whale.crm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.whale.crm.ai.application.AiWorkerClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiWorkerClientTest {
    private HttpServer server;
    private AiWorkerClient client;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"provider\":\"mock\",\"model\":\"mock-rules-v1\",\"insight\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        client = new AiWorkerClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort(), "internal-token", 3000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void dynamicProviderConfigUsesSnakeCaseAndContainsPlaintextOnlyInsideInternalRequest() throws Exception {
        AiWorkerClient.ProviderConfig provider = new AiWorkerClient.ProviderConfig("openai_compatible",
                "https://provider.example.com/v1", "sk-internal-test", "model-a", 17);

        client.extract(9L, 10L, List.of(), List.of(), List.of(), provider);

        JsonNode body = mapper.readTree(requestBody.get());
        assertThat(requestPath.get()).isEqualTo("/api/v1/insights/extract");
        assertThat(body.at("/provider_config/provider").asText()).isEqualTo("openai_compatible");
        assertThat(body.at("/provider_config/base_url").asText()).isEqualTo("https://provider.example.com/v1");
        assertThat(body.at("/provider_config/api_key").asText()).isEqualTo("sk-internal-test");
        assertThat(body.at("/provider_config/model").asText()).isEqualTo("model-a");
        assertThat(body.at("/provider_config/timeout_seconds").asInt()).isEqualTo(17);
    }

    @Test
    void missingProviderConfigOmitsFieldForEnvironmentFallback() throws Exception {
        client.extract(9L, 10L, List.of(), List.of(), List.of(), null);

        assertThat(mapper.readTree(requestBody.get()).has("provider_config")).isFalse();
    }

    @Test
    void connectionTestReusesExtractionEndpointWithSyntheticMessageAndDynamicConfig() throws Exception {
        AiWorkerClient.ProviderConfig provider = new AiWorkerClient.ProviderConfig("mock", null, null, null, 30);

        client.testConnection(provider);

        JsonNode body = mapper.readTree(requestBody.get());
        assertThat(requestPath.get()).isEqualTo("/api/v1/insights/extract");
        assertThat(body.at("/provider_config/provider").asText()).isEqualTo("mock");
        assertThat(body.at("/provider_config/base_url").asText()).isEmpty();
        assertThat(body.at("/provider_config/api_key").asText()).isEmpty();
        assertThat(body.at("/provider_config/model").asText()).isEmpty();
        assertThat(body.at("/messages/0/id").asText()).isEqualTo("connection-test");
        assertThat(body.at("/messages/0/text").asText()).doesNotContain("api", "key");
    }
}
