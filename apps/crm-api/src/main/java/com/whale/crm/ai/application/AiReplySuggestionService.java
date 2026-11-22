package com.whale.crm.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.AiReplyDecisionRequest;
import com.whale.crm.ai.api.dto.AiReplySuggestionResponse;
import com.whale.crm.ai.api.dto.GenerateAiReplyRequest;
import com.whale.crm.ai.domain.AiReplySuggestion;
import com.whale.crm.ai.domain.AiReplySuggestionRepository;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.quickreply.application.QuickReplyFactsProvider;
import com.whale.crm.quickreply.application.QuickReplyPreviewService;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import com.whale.crm.quickreply.domain.QuickReplyTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AiReplySuggestionService {
    private static final String CHAT_CONTEXT_COUNT_KEY = "_chat_context_count";
    private static final Set<String> FACT_CODES = Set.of("customer_name", "manager_name", "project_name",
            "total_count", "used_count", "remaining_count", "last_visit_date", "last_writeoff_date",
            "appointment_date", "appointment_time", "institution_name", "institution_address", "doctor_name",
            "expire_date", "order_no");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9])\\d+(?:[.-]\\d+)*(?![A-Za-z0-9])");

    private final AiReplySuggestionRepository repository;
    private final AiWorkerClient workerClient;
    private final AiProviderConfigService providerConfigService;
    private final QuickReplyPreviewService previewService;
    private final QuickReplyFactsProvider factsProvider;
    private final AiReplyChatContextProvider chatContextProvider;
    private final QuickReplyTextMasker masker;
    private final CustomerScopeSupport scopeSupport;
    private final AiChatDelegationSafety safety;
    private final AesGcmCipher cipher;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public AiReplySuggestionService(AiReplySuggestionRepository repository, AiWorkerClient workerClient,
                                    AiProviderConfigService providerConfigService,
                                    QuickReplyPreviewService previewService, QuickReplyFactsProvider factsProvider,
                                    AiReplyChatContextProvider chatContextProvider,
                                    QuickReplyTextMasker masker, CustomerScopeSupport scopeSupport,
                                    AiChatDelegationSafety safety, AesGcmCipher cipher, AuditService auditService,
                                    ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.workerClient = workerClient;
        this.providerConfigService = providerConfigService;
        this.previewService = previewService;
        this.factsProvider = factsProvider;
        this.chatContextProvider = chatContextProvider;
        this.masker = masker;
        this.scopeSupport = scopeSupport;
        this.safety = safety;
        this.cipher = cipher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AiReplySuggestionResponse generate(CurrentUser user, GenerateAiReplyRequest request, String key) {
        String instruction = request.instruction() == null ? "" : request.instruction().strip();
        String requestHash = IdempotencyService.sha256(payload(request.customerId(), request.templateId(), instruction));
        var replay = repository.findByGenerationKey(user.tenantId(), user.userId(), key);
        if (replay.isPresent()) {
            requireSame(replay.get().requestHash(), requestHash);
            return view(replay.get());
        }
        QuickReplyTemplate template = previewService.requireAiTemplate(user, request.templateId());
        Map<String, Object> facts = safeFacts(factsProvider.load(user.tenantId(), user, request.customerId()));
        List<AiWorkerClient.MessageInput> recentMessages = chatContextProvider.loadRecentConsentedMessages(
                user, request.customerId());
        int chatContextCount = recentMessages.size();
        AiWorkerClient.ReplyGenerationResult generated;
        try {
            generated = workerClient.generateReply(UUID.randomUUID().toString(), request.customerId(), template.scene(),
                    facts, instruction, recentMessages, providerConfigService.workerConfig(user.tenantId()));
        } catch (AiWorkerException ex) {
            ErrorCode code = ex.getMessage() != null && (ex.getMessage().contains("未启用") || ex.getMessage().contains("配置不完整"))
                    ? ErrorCode.AI_PROVIDER_DISABLED : ErrorCode.AI_PROVIDER_FAILED;
            throw new BusinessException(code, ex.getMessage() == null ? "AI 回复生成失败，请稍后重试" : ex.getMessage());
        }
        ValidatedReply candidate = validate(generated, template.scene(), facts);
        Map<String, Object> persistedFactsSnapshot = factsSnapshot(facts, chatContextCount);
        try {
            AiReplySuggestion saved = transactions.execute(status -> {
                var existing = repository.findByGenerationKey(user.tenantId(), user.userId(), key);
                if (existing.isPresent()) {
                    requireSame(existing.get().requestHash(), requestHash);
                    return existing.get();
                }
                previewService.requireAiTemplate(user, request.templateId());
                scopeSupport.requireVisible(user.tenantId(), request.customerId(), user);
                AiReplySuggestion input = new AiReplySuggestion(null, user.tenantId(), template.id(), request.customerId(),
                        user.userId(), template.scene(), json(persistedFactsSnapshot), requestHash, key,
                        cipher.encrypt(candidate.reply()),
                        candidate.reason(), json(candidate.usedFactCodes()), candidate.provider(), candidate.model(),
                        candidate.promptVersion(), candidate.confidence(), "PROPOSED", false, false, null, null,
                        null, null, null, null, null, null, 0);
                repository.insert(input);
                AiReplySuggestion created = repository.findByGenerationKey(user.tenantId(), user.userId(), key).orElseThrow();
                auditService.record(user.tenantId(), user.userId(), "AI_REPLY_GENERATED", "AI_REPLY_SUGGESTION",
                        created.id().toString(), null, auditSnapshot(created), null);
                return created;
            });
            return view(Objects.requireNonNull(saved));
        } catch (DuplicateKeyException ex) {
            AiReplySuggestion existing = repository.findByGenerationKey(user.tenantId(), user.userId(), key)
                    .orElseThrow(() -> ex);
            requireSame(existing.requestHash(), requestHash);
            return view(existing);
        }
    }

    @Transactional(readOnly = true)
    public AiReplySuggestionResponse get(CurrentUser user, long id) {
        return view(ownedVisible(user, id));
    }

    @Transactional(readOnly = true)
    public List<AiReplySuggestionResponse> history(CurrentUser user, long customerId, int size) {
        scopeSupport.requireVisible(user.tenantId(), customerId, user);
        return repository.findForCustomer(user.tenantId(), user.userId(), customerId, size).stream().map(this::view).toList();
    }

    @Transactional
    public AiReplySuggestionResponse decide(CurrentUser user, long id, AiReplyDecisionRequest request, String key) {
        boolean accepted = "ACCEPT".equals(request.decision());
        String finalContent = accepted ? requiredText(request.finalContent(), "采纳回复", 4000) : null;
        if (!accepted && request.finalContent() != null && !request.finalContent().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "驳回候选不能带最终回复");
        }
        String reason = masker.required(request.reason(), "处理原因", 500);
        String decisionHash = IdempotencyService.sha256(payload(id, request.decision(), finalContent, reason));
        var replay = repository.findByDecisionKey(user.tenantId(), user.userId(), key);
        if (replay.isPresent()) {
            requireSame(replay.get().decisionHash(), decisionHash);
            return view(replay.get());
        }
        AiReplySuggestion current = repository.findByIdForUpdate(user.tenantId(), user.userId(), id).orElseThrow(this::notFound);
        scopeSupport.requireVisible(user.tenantId(), current.customerId(), user);
        if (current.version() != request.version()) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 回复已被处理，请刷新后重试");
        }
        if (!"PROPOSED".equals(current.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 回复已完成人工处理，不能重复决策");
        }
        String original = cipher.decrypt(current.replyCipher());
        boolean edited = accepted && !original.equals(finalContent);
        String status = accepted ? "ACCEPTED" : "REJECTED";
        if (repository.decide(user.tenantId(), user.userId(), id, current.version(), status, accepted, edited,
                finalContent == null ? null : cipher.encrypt(finalContent), reason, user.userId(), Instant.now(), key,
                decisionHash) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 回复已被处理，请刷新后重试");
        }
        AiReplySuggestion saved = repository.findById(user.tenantId(), user.userId(), id).orElseThrow(this::notFound);
        auditService.record(user.tenantId(), user.userId(), "AI_REPLY_" + status, "AI_REPLY_SUGGESTION",
                Long.toString(id), Map.of("status", current.status()), auditSnapshot(saved), reason);
        return view(saved);
    }

    @Transactional(readOnly = true)
    public AcceptedAiReply acceptedForUsage(CurrentUser user, long id, long customerId) {
        AiReplySuggestion suggestion = repository.findById(user.tenantId(), user.userId(), id).orElseThrow(this::notFound);
        scopeSupport.requireVisible(user.tenantId(), customerId, user);
        if (suggestion.customerId() != customerId || !"ACCEPTED".equals(suggestion.status())
                || !suggestion.accepted() || suggestion.finalContentCipher() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "AI 回复必须由所属员工针对当前客户采纳后才能记录使用");
        }
        return new AcceptedAiReply(suggestion.templateId(), suggestion.scene(), cipher.decrypt(suggestion.replyCipher()),
                cipher.decrypt(suggestion.finalContentCipher()), suggestion.edited());
    }

    private ValidatedReply validate(AiWorkerClient.ReplyGenerationResult result, String scene, Map<String, Object> facts) {
        if (result == null || result.suggestion() == null) {
            throw invalidOutput();
        }
        AiWorkerClient.ReplySuggestionData data = result.suggestion();
        String reply = requiredText(data.reply(), "AI 回复", 2000);
        String reason = masker.required(data.reason(), "AI 推荐理由", 500);
        if (!scene.equals(data.scene()) || !"UNVERIFIED".equals(data.factStatus())
                || !Boolean.FALSE.equals(data.allowFinancialWrite()) || !Boolean.TRUE.equals(data.requiresHumanReview())
                || data.confidence() == null || data.confidence().compareTo(BigDecimal.ZERO) < 0
                || data.confidence().compareTo(BigDecimal.ONE) > 0 || data.usedFactCodes() == null
                || data.usedFactCodes().size() != Set.copyOf(data.usedFactCodes()).size()
                || !FACT_CODES.containsAll(data.usedFactCodes())
                || safety.replyBlockReason(reply, List.of()).isPresent()) {
            throw invalidOutput();
        }
        for (String code : data.usedFactCodes()) {
            Object value = facts.get(code);
            if (value == null || !reply.contains(value.toString())) {
                throw invalidOutput();
            }
        }
        var numbers = NUMBER.matcher(reply);
        while (numbers.find()) {
            String number = numbers.group();
            if (facts.values().stream().noneMatch(value -> value.toString().contains(number))) {
                throw invalidOutput();
            }
        }
        return new ValidatedReply(reply, reason, List.copyOf(data.usedFactCodes()),
                requiredText(result.provider(), "AI 供应商", 48), requiredText(result.model(), "AI 模型", 128),
                requiredText(result.promptVersion(), "提示词版本", 48), data.confidence());
    }

    private Map<String, Object> safeFacts(Map<String, Object> input) {
        Map<String, Object> safe = new TreeMap<>();
        input.forEach((code, value) -> {
            if (!FACT_CODES.contains(code) || value == null) {
                return;
            }
            if (value instanceof Number number) {
                safe.put(code, number.longValue());
            } else {
                safe.put(code, masker.mask(value.toString(), 256));
            }
        });
        return Map.copyOf(safe);
    }

    private AiReplySuggestion ownedVisible(CurrentUser user, long id) {
        AiReplySuggestion suggestion = repository.findById(user.tenantId(), user.userId(), id).orElseThrow(this::notFound);
        scopeSupport.requireVisible(user.tenantId(), suggestion.customerId(), user);
        return suggestion;
    }

    private AiReplySuggestionResponse view(AiReplySuggestion suggestion) {
        int chatContextCount = extractChatContextCount(suggestion.factsSnapshotJson());
        return new AiReplySuggestionResponse(suggestion.id(), suggestion.templateId(), suggestion.customerId(),
                suggestion.scene(), cipher.decrypt(suggestion.replyCipher()), suggestion.reasonMasked(),
                stringList(suggestion.usedFactCodesJson()), suggestion.provider(), suggestion.modelName(),
                suggestion.promptVersion(), suggestion.confidence(), suggestion.status(), suggestion.accepted(),
                suggestion.edited(), cipher.decrypt(suggestion.finalContentCipher()), suggestion.createdAt(),
                suggestion.decisionAt(), suggestion.version(), chatContextCount > 0, chatContextCount);
    }

    private Map<String, Object> factsSnapshot(Map<String, Object> facts, int chatContextCount) {
        Map<String, Object> snapshot = new LinkedHashMap<>(facts);
        if (chatContextCount > 0) {
            snapshot.put(CHAT_CONTEXT_COUNT_KEY, chatContextCount);
        }
        return snapshot;
    }

    private int extractChatContextCount(String factsSnapshotJson) {
        if (factsSnapshotJson == null || factsSnapshotJson.isBlank()) {
            return 0;
        }
        try {
            var node = objectMapper.readTree(factsSnapshotJson);
            if (node != null && node.isObject() && node.has(CHAT_CONTEXT_COUNT_KEY)) {
                return Math.max(0, node.path(CHAT_CONTEXT_COUNT_KEY).asInt(0));
            }
            return 0;
        } catch (JsonProcessingException ex) {
            return 0;
        }
    }

    private List<String> stringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception ex) {
            throw new IllegalStateException("AI 回复事实引用快照损坏", ex);
        }
    }

    private String requiredText(String value, String label, int maxLength) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength
                || value.chars().anyMatch(character -> Character.isISOControl(character)
                && character != '\n' && character != '\r' && character != '\t')) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能为空、超长或包含无效字符");
        }
        return value.strip();
    }

    private Map<String, Object> auditSnapshot(AiReplySuggestion suggestion) {
        int chatContextCount = extractChatContextCount(suggestion.factsSnapshotJson());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("customerId", suggestion.customerId());
        snapshot.put("templateId", suggestion.templateId());
        snapshot.put("scene", suggestion.scene());
        snapshot.put("status", suggestion.status());
        snapshot.put("provider", suggestion.provider());
        snapshot.put("model", suggestion.modelName());
        snapshot.put("promptVersion", suggestion.promptVersion());
        snapshot.put("accepted", suggestion.accepted());
        snapshot.put("edited", suggestion.edited());
        snapshot.put("chatContextIncluded", chatContextCount > 0);
        snapshot.put("chatContextCount", chatContextCount);
        return snapshot;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("AI 回复快照序列化失败", ex);
        }
    }

    private String payload(Object... values) {
        return json(values);
    }

    private void requireSame(String expected, String actual) {
        if (expected == null || !expected.equals(actual)) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等键已用于不同的 AI 回复请求");
        }
    }

    private BusinessException invalidOutput() {
        return new BusinessException(ErrorCode.AI_PROVIDER_FAILED, "AI 回复未通过结构、安全或事实校验");
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "AI 回复不存在或不在本人客户范围内");
    }

    private record ValidatedReply(String reply, String reason, List<String> usedFactCodes, String provider,
                                  String model, String promptVersion, BigDecimal confidence) {
    }

    public record AcceptedAiReply(long templateId, String scene, String originalContent, String finalContent,
                                  boolean edited) {
    }
}
