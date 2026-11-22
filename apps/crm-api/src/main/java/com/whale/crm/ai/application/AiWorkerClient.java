package com.whale.crm.ai.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class AiWorkerClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String token;

    public AiWorkerClient(ObjectMapper mapper,
                          @Value("${app.ai.worker.base-url:http://localhost:8090}") String baseUrl,
                          @Value("${app.ai.worker.internal-token:dev-ai-internal-token-change-me}") String token,
                          @Value("${app.ai.worker.timeout-ms:30000}") long timeoutMs) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        long boundedTimeoutMs = Math.max(1_000L, Math.min(300_000L, timeoutMs));
        this.http = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(boundedTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(boundedTimeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    public ExtractionResult extract(long jobId, long customerId, List<MessageInput> messages,
                                    List<ProjectInput> projects, List<InstitutionInput> institutions) {
        return extract(jobId, customerId, messages, projects, institutions, null);
    }

    public ExtractionResult extract(long jobId, long customerId, List<MessageInput> messages,
                                    List<ProjectInput> projects, List<InstitutionInput> institutions,
                                    ProviderConfig providerConfig) {
        try {
            String json = mapper.writeValueAsString(new WorkerRequest(Long.toString(jobId), Long.toString(customerId),
                    messages, projects, institutions, providerConfig));
            Request request = new Request.Builder().url(baseUrl + "/api/v1/insights/extract")
                    .header("X-AI-Internal-Token", token).post(RequestBody.create(json, JSON)).build();
            try (var response = http.newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new AiWorkerException(response.code() >= 500 ? "AI 模型服务暂时不可用" : "AI 输出未通过校验", response.code() < 500);
                }
                return mapper.readValue(body, ExtractionResult.class);
            }
        } catch (AiWorkerException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiWorkerException("AI 模型服务暂时不可用", false);
        }
    }

    public ReplyGenerationResult generateReply(String suggestionId, long customerId, String scene,
                                               java.util.Map<String, Object> facts, String instruction,
                                               ProviderConfig providerConfig) {
        return generateReply(suggestionId, customerId, scene, facts, instruction, List.of(), providerConfig);
    }

    public ReplyGenerationResult generateReply(String suggestionId, long customerId, String scene,
                                               java.util.Map<String, Object> facts, String instruction,
                                               List<MessageInput> recentMessages,
                                               ProviderConfig providerConfig) {
        try {
            List<MessageInput> safeMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
            String json = mapper.writeValueAsString(new ReplyGenerationRequest(suggestionId, Long.toString(customerId),
                    scene, facts, instruction, safeMessages, providerConfig));
            Request request = new Request.Builder().url(baseUrl + "/api/v1/replies/generate")
                    .header("X-AI-Internal-Token", token).post(RequestBody.create(json, JSON)).build();
            try (var response = http.newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new AiWorkerException(response.code() == 503 ? "AI 模型服务未启用或配置不完整" : "AI 回复生成失败，请稍后重试",
                            response.code() < 500 || response.code() == 503);
                }
                return mapper.readValue(body, ReplyGenerationResult.class);
            }
        } catch (AiWorkerException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiWorkerException("AI 模型服务暂时不可用", false);
        }
    }

    public void testConnection(ProviderConfig providerConfig) {
        extract(0L, 0L,
                List.of(new MessageInput("connection-test", "CUSTOMER", Instant.now(), "连接测试，不生成业务操作")),
                List.of(), List.of(), providerConfig);
    }

    public record ReplyGenerationRequest(@JsonProperty("suggestion_id") String suggestionId,
                                         @JsonProperty("customer_id") String customerId, String scene,
                                         java.util.Map<String, Object> facts, String instruction,
                                         @JsonProperty("recent_messages") @JsonInclude(JsonInclude.Include.NON_EMPTY)
                                         List<MessageInput> recentMessages,
                                         @JsonProperty("provider_config") @JsonInclude(JsonInclude.Include.NON_NULL)
                                         ProviderConfig providerConfig) {
        public ReplyGenerationRequest(String suggestionId, String customerId, String scene,
                                      java.util.Map<String, Object> facts, String instruction,
                                      ProviderConfig providerConfig) {
            this(suggestionId, customerId, scene, facts, instruction, List.of(), providerConfig);
        }

        public ReplyGenerationRequest {
            facts = facts == null ? null : java.util.Map.copyOf(facts);
            recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ReplyGenerationResult(String provider, String model,
                                        @JsonProperty("prompt_version") String promptVersion,
                                        ReplySuggestionData suggestion) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ReplySuggestionData(String scene, String reply, String reason, BigDecimal confidence,
                                      @JsonProperty("used_fact_codes") List<String> usedFactCodes,
                                      @JsonProperty("fact_status") String factStatus,
                                      @JsonProperty("allow_financial_write") Boolean allowFinancialWrite,
                                      @JsonProperty("requires_human_review") Boolean requiresHumanReview) {
        public ReplySuggestionData {
            usedFactCodes = usedFactCodes == null ? null : List.copyOf(usedFactCodes);
        }
    }
    public record WorkerRequest(@JsonProperty("job_id") String jobId,
                                @JsonProperty("customer_id") String customerId,
                                List<MessageInput> messages,
                                List<ProjectInput> projects,
                                List<InstitutionInput> institutions,
                                @JsonProperty("provider_config") @JsonInclude(JsonInclude.Include.NON_NULL)
                                ProviderConfig providerConfig) {
        public WorkerRequest {
            messages = messages == null ? null : List.copyOf(messages);
            projects = projects == null ? null : List.copyOf(projects);
            institutions = institutions == null ? null : List.copyOf(institutions);
        }
    }
    public record ProviderConfig(String provider, @JsonProperty("base_url") String baseUrl,
                                 @JsonProperty("api_key") String apiKey, String model,
                                 @JsonProperty("timeout_seconds") int timeoutSeconds) {
        public ProviderConfig {
            baseUrl = baseUrl == null ? "" : baseUrl;
            apiKey = apiKey == null ? "" : apiKey;
            model = model == null ? "" : model;
        }
    }
    public record MessageInput(String id, @JsonProperty("sender_type") String senderType,
                               @JsonProperty("sent_at") Instant sentAt, String text) { }
    public record ProjectInput(String code, String name) { }
    public record InstitutionInput(String id, String name) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractionResult(String provider, String model, InsightData insight) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record InsightData(@JsonProperty("insight_type") String insightType,
                              @JsonProperty("project_codes") List<String> projectCodes,
                              @JsonProperty("mentioned_institution") String mentionedInstitution,
                              @JsonProperty("mentioned_time") String mentionedTime,
                              @JsonProperty("absolute_time") Instant absoluteTime,
                              @JsonProperty("evidence_message_ids") List<String> evidenceMessageIds,
                              BigDecimal confidence,
                              @JsonProperty("proposed_actions") List<String> proposedActions,
                              @JsonProperty("fact_status") String factStatus,
                              @JsonProperty("allow_financial_write") Boolean allowFinancialWrite,
                              @JsonProperty("reasoning_summary") String reasoningSummary,
                              @JsonProperty("reply_draft") String replyDraft,
                              @JsonProperty("followup_draft") Object followupDraft,
                              @JsonProperty("risk_flags") List<String> riskFlags) {
        public InsightData {
            projectCodes = projectCodes == null ? null : List.copyOf(projectCodes);
            evidenceMessageIds = evidenceMessageIds == null ? null : List.copyOf(evidenceMessageIds);
            proposedActions = proposedActions == null ? null : List.copyOf(proposedActions);
            riskFlags = riskFlags == null ? null : List.copyOf(riskFlags);
        }
    }
}
