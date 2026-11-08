package com.whale.crm.ai.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class KnowledgeWorkerClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String token;

    public KnowledgeWorkerClient(ObjectMapper mapper,
                                 @Value("${app.ai.worker.base-url:http://localhost:8090}") String baseUrl,
                                 @Value("${app.ai.worker.internal-token:dev-ai-internal-token-change-me}") String token,
                                 @Value("${app.ai.worker.timeout-ms:30000}") long timeoutMs) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.http = new OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS).writeTimeout(timeoutMs, TimeUnit.MILLISECONDS).build();
    }

    public void testConnection(ProviderConfig providerConfig, long tenantId, String requestId) {
        String traceId = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        try {
            WorkerRequest payload = new WorkerRequest("连接测试，不生成业务操作",
                    "tenant-" + tenantId + "-knowledge-config-test", null, providerConfig);
            Request request = new Request.Builder().url(baseUrl + "/api/v1/knowledge/chat")
                    .header("X-AI-Internal-Token", token).header("X-Request-Id", traceId)
                    .post(RequestBody.create(mapper.writeValueAsString(payload), JSON)).build();
            OkHttpClient client = http.newBuilder()
                    .readTimeout(providerConfig.timeoutSeconds(), TimeUnit.SECONDS).build();
            try (var response = client.newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new AiWorkerException(response.code() >= 500 ? "Dify 知识服务暂时不可用" : "Dify 输出未通过校验",
                            response.code() < 500);
                }
                WorkerResponse result = mapper.readValue(body, WorkerResponse.class);
                if (!traceId.equals(result.requestId()) || !providerConfig.provider().equals(result.provider())
                        || result.answer() == null || result.answer().isBlank()) {
                    throw new AiWorkerException("Dify 输出未通过结构校验", true);
                }
            }
        } catch (AiWorkerException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new AiWorkerException("Dify 知识服务暂时不可用", false);
        }
    }

    public DocumentSyncResult syncDocument(DatasetProviderConfig config, DocumentSyncInput input, String requestId) {
        String traceId = traceId(requestId);
        String body = postDocument("/api/v1/knowledge/documents/sync",
                new DocumentSyncRequest(input.jobId(), input.name(), input.text(), input.metadata(), input.documentId(), config),
                config, traceId);
        return documentResult(body, config.provider(), traceId);
    }

    public DocumentSyncResult documentStatus(DatasetProviderConfig config, String documentId, String batch, String requestId) {
        String traceId = traceId(requestId);
        String body = postDocument("/api/v1/knowledge/documents/status",
                new DocumentStatusRequest(batch, documentId, config), config, traceId);
        return documentResult(body, config.provider(), traceId);
    }

    public void disableDocument(DatasetProviderConfig config, String documentId, String requestId) {
        String traceId = traceId(requestId);
        String body = postDocument("/api/v1/knowledge/documents/disable",
                new DocumentDisableRequest(documentId, config), config, traceId);
        try {
            DocumentDisableResult result = mapper.readValue(body, DocumentDisableResult.class);
            if (!traceId.equals(result.requestId()) || !config.provider().equals(result.provider())
                    || documentId == null || !documentId.equals(result.documentId()) || !result.disabled()) {
                throw new AiWorkerException("Dify 停用响应未通过结构校验", true);
            }
        } catch (AiWorkerException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiWorkerException("Dify 停用响应未通过结构校验", true);
        }
    }

    private String postDocument(String path, Object payload, DatasetProviderConfig config, String requestId) {
        try {
            Request request = new Request.Builder().url(baseUrl + path)
                    .header("X-AI-Internal-Token", token).header("X-Request-Id", requestId)
                    .post(RequestBody.create(mapper.writeValueAsString(payload), JSON)).build();
            OkHttpClient client = http.newBuilder().readTimeout(config.timeoutSeconds(), TimeUnit.SECONDS).build();
            try (var response = client.newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new AiWorkerException(response.code() >= 500 ? "Dify Dataset 服务暂时不可用" : "Dify 输出未通过校验",
                            response.code() < 500);
                }
                return body;
            }
        } catch (AiWorkerException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiWorkerException("Dify Dataset 服务暂时不可用", false);
        }
    }

    private DocumentSyncResult documentResult(String body, String provider, String requestId) {
        try {
            DocumentSyncResult result = mapper.readValue(body, DocumentSyncResult.class);
            if (!requestId.equals(result.requestId()) || !provider.equals(result.provider())
                    || result.documentId() == null || result.documentId().isBlank()
                    || result.batch() == null || result.batch().isBlank()
                    || !java.util.Set.of("INDEXING", "AVAILABLE", "FAILED").contains(result.indexingStatus())) {
                throw new AiWorkerException("Dify 文档响应未通过结构校验", true);
            }
            return result;
        } catch (AiWorkerException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiWorkerException("Dify 文档响应未通过结构校验", true);
        }
    }

    private String traceId(String requestId) {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentSyncResult(String requestId, String provider,
                                     @JsonProperty("document_id") String documentId, String batch,
                                     @JsonProperty("indexing_status") String indexingStatus,
                                     @JsonProperty("error_masked") String errorMasked) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentDisableResult(String requestId, String provider,
                                        @JsonProperty("document_id") String documentId, boolean disabled) { }
    public record DatasetProviderConfig(String provider, @JsonProperty("base_url") String baseUrl,
                                        @JsonProperty("api_key") String apiKey,
                                        @JsonProperty("dataset_id") String datasetId,
                                        @JsonProperty("timeout_seconds") int timeoutSeconds) {
        public DatasetProviderConfig {
            baseUrl = baseUrl == null ? "" : baseUrl;
            apiKey = apiKey == null ? "" : apiKey;
            datasetId = datasetId == null ? "" : datasetId;
        }
    }
    public record DocumentMetadata(@JsonProperty("tenant_id") String tenantId,
                                   @JsonProperty("material_id") String materialId,
                                   @JsonProperty("revision_id") String revisionId,
                                   @JsonProperty("revision_no") int revisionNo,
                                   @JsonProperty("category_code") String categoryCode,
                                   @JsonProperty("institution_id") String institutionId,
                                   @JsonProperty("project_id") String projectId,
                                   String visibility,
                                   @JsonProperty("content_hash") String contentHash) { }
    public record DocumentSyncInput(@JsonProperty("job_id") String jobId, String name, String text,
                                    DocumentMetadata metadata,
                                    @JsonProperty("document_id") String documentId) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentSyncRequest(@JsonProperty("job_id") String jobId, String name, String text,
                                      DocumentMetadata metadata, @JsonProperty("document_id") String documentId,
                                      @JsonProperty("dataset_config") DatasetProviderConfig datasetConfig) { }
    public record DocumentStatusRequest(String batch, @JsonProperty("document_id") String documentId,
                                        @JsonProperty("dataset_config") DatasetProviderConfig datasetConfig) { }
    public record DocumentDisableRequest(@JsonProperty("document_id") String documentId,
                                         @JsonProperty("dataset_config") DatasetProviderConfig datasetConfig) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkerRequest(String question, @JsonProperty("end_user_id") String endUserId,
                                @JsonProperty("conversation_id") String conversationId,
                                @JsonProperty("provider_config") ProviderConfig providerConfig) { }
    public record ProviderConfig(String provider, @JsonProperty("base_url") String baseUrl,
                                 @JsonProperty("api_key") String apiKey,
                                 @JsonProperty("timeout_seconds") int timeoutSeconds) {
        public ProviderConfig {
            baseUrl = baseUrl == null ? "" : baseUrl;
            apiKey = apiKey == null ? "" : apiKey;
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkerResponse(String requestId, String provider, String answer) { }
}
