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
            String responseBody = "/api/v1/replies/generate".equals(requestPath.get())
                    ? "{\"provider\":\"mock\",\"model\":\"mock-reply-rules-v1\",\"prompt_version\":\"reply-v1\","
                    + "\"suggestion\":{\"scene\":\"PACKAGE_REMAINING\",\"reply\":\"测试客户您好，还剩6次。\","
                    + "\"reason\":\"只读事实\",\"confidence\":0.86,\"used_fact_codes\":[\"customer_name\",\"remaining_count\"],"
                    + "\"fact_status\":\"UNVERIFIED\",\"allow_financial_write\":false,\"requires_human_review\":true}}"
                    : "{\"provider\":\"mock\",\"model\":\"mock-rules-v1\",\"insight\":null}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
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
    void replyGenerationUsesRealHttpContractAndParsesSafetyFields() throws Exception {
        AiWorkerClient.ProviderConfig provider = new AiWorkerClient.ProviderConfig("mock", "", "", "mock", 30);

        AiWorkerClient.ReplyGenerationResult result = client.generateReply("candidate-1", 128L,
                "PACKAGE_REMAINING", java.util.Map.of("customer_name", "测试客户", "remaining_count", 6),
                "语气简洁", provider);

        JsonNode body = mapper.readTree(requestBody.get());
        assertThat(requestPath.get()).isEqualTo("/api/v1/replies/generate");
        assertThat(body.at("/suggestion_id").asText()).isEqualTo("candidate-1");
        assertThat(body.at("/customer_id").asText()).isEqualTo("128");
        assertThat(body.at("/scene").asText()).isEqualTo("PACKAGE_REMAINING");
        assertThat(body.at("/facts/remaining_count").asInt()).isEqualTo(6);
        assertThat(body.at("/instruction").asText()).isEqualTo("语气简洁");
        assertThat(body.at("/provider_config/provider").asText()).isEqualTo("mock");
        assertThat(result.promptVersion()).isEqualTo("reply-v1");
        assertThat(result.suggestion().reply()).contains("还剩6次");
        assertThat(result.suggestion().confidence()).isEqualByComparingTo("0.86");
        assertThat(result.suggestion().allowFinancialWrite()).isFalse();
        assertThat(result.suggestion().requiresHumanReview()).isTrue();
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
