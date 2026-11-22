package com.whale.crm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.AiReplySuggestionResponse;
import com.whale.crm.ai.api.dto.GenerateAiReplyRequest;
import com.whale.crm.ai.application.AiChatDelegationSafety;
import com.whale.crm.ai.application.AiProviderConfigService;
import com.whale.crm.ai.application.AiReplyChatContextProvider;
import com.whale.crm.ai.application.AiReplySuggestionService;
import com.whale.crm.ai.application.AiWorkerClient;
import com.whale.crm.ai.domain.AiReplySuggestion;
import com.whale.crm.ai.domain.AiReplySuggestionRepository;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CryptoProperties;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.quickreply.application.QuickReplyFactsProvider;
import com.whale.crm.quickreply.application.QuickReplyPreviewService;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import com.whale.crm.quickreply.domain.QuickReplyTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiReplySuggestionServiceTest {
    @Mock
    private AiReplySuggestionRepository repository;
    @Mock
    private AiWorkerClient workerClient;
    @Mock
    private AiProviderConfigService providerConfigService;
    @Mock
    private QuickReplyPreviewService previewService;
    @Mock
    private QuickReplyFactsProvider factsProvider;
    @Mock
    private AiReplyChatContextProvider chatContextProvider;
    @Mock
    private CustomerScopeSupport scopeSupport;
    @Mock
    private AuditService auditService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AiReplySuggestionService service;
    private AesGcmCipher cipher;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) (i + 1);
        }
        cipher = new AesGcmCipher(new CryptoProperties(Base64.getEncoder().encodeToString(keyBytes), "test-pepper"));
        objectMapper = new ObjectMapper().findAndRegisterModules();
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new AiReplySuggestionService(repository, workerClient, providerConfigService, previewService,
                factsProvider, chatContextProvider, new QuickReplyTextMasker(), scopeSupport,
                new AiChatDelegationSafety(), cipher, auditService, objectMapper, transactionManager);
    }

    @Test
    void generateIncludesConsentedChatContextAndPersistsTransparencyCountWithoutPlaintextChat() {
        CurrentUser user = new CurrentUser(10L, 1L, "steward", "管家", "EMPLOYEE", 2L,
                Set.of("STEWARD"), Set.of("ai:write", "ai:read", "quickreply:view", "wecom:read"), Set.of());
        QuickReplyTemplate template = new QuickReplyTemplate(5L, 1L, 1L, "余次提醒", "PROJECT_REMAINING", "TEXT",
                "还剩{remaining_count}次", "PACKAGE_REMAINING", "GLOBAL", null, true, true, true, false,
                "ACTIVE", 1, 1L, Instant.now(), Instant.now(), 0L);
        when(previewService.requireAiTemplate(user, 5L)).thenReturn(template);
        when(factsProvider.load(1L, user, 100L)).thenReturn(Map.of("customer_name", "林女士", "remaining_count", 6));
        List<AiWorkerClient.MessageInput> recent = List.of(
                new AiWorkerClient.MessageInput("11", "CUSTOMER", Instant.parse("2026-11-22T08:00:00Z"), "请问还剩几次？"),
                new AiWorkerClient.MessageInput("12", "EMPLOYEE", Instant.parse("2026-11-22T08:01:00Z"), "马上为您查询"));
        when(chatContextProvider.loadRecentConsentedMessages(user, 100L)).thenReturn(recent);
        when(workerClient.generateReply(anyString(), eq(100L), eq("PACKAGE_REMAINING"), anyMap(), eq(""),
                eq(recent), nullable(AiWorkerClient.ProviderConfig.class)))
                .thenReturn(new AiWorkerClient.ReplyGenerationResult("mock", "mock-reply-rules-v1", "reply-v1",
                        new AiWorkerClient.ReplySuggestionData("PACKAGE_REMAINING", "林女士您好，还剩6次。",
                                "结合已授权聊天上下文与CRM事实", new BigDecimal("0.85"),
                                List.of("customer_name", "remaining_count"), "UNVERIFIED", false, true)));

        AtomicReference<AiReplySuggestion> stored = new AtomicReference<>();
        when(repository.findByGenerationKey(1L, 10L, "k-1")).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        doAnswer(inv -> {
            AiReplySuggestion input = inv.getArgument(0);
            stored.set(new AiReplySuggestion(99L, input.tenantId(), input.templateId(), input.customerId(),
                    input.employeeId(), input.scene(), input.factsSnapshotJson(), input.requestHash(),
                    input.idempotencyKey(), input.replyCipher(), input.reasonMasked(), input.usedFactCodesJson(),
                    input.provider(), input.modelName(), input.promptVersion(), input.confidence(), input.status(),
                    false, false, null, null, null, null, null, null, Instant.now(), Instant.now(), 0L));
            return null;
        }).when(repository).insert(any(AiReplySuggestion.class));

        AiReplySuggestionResponse response = service.generate(user, new GenerateAiReplyRequest(100L, 5L, null), "k-1");

        assertThat(response.chatContextIncluded()).isTrue();
        assertThat(response.chatContextCount()).isEqualTo(2);
        assertThat(response.reply()).isEqualTo("林女士您好，还剩6次。");
        assertThat(stored.get().factsSnapshotJson())
                .contains("\"_chat_context_count\":2")
                .doesNotContain("请问还剩几次", "马上为您查询");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(1L), eq(10L), eq("AI_REPLY_GENERATED"), eq("AI_REPLY_SUGGESTION"),
                eq("99"), isNull(), auditCaptor.capture(), isNull());
        assertThat(auditCaptor.getValue())
                .containsEntry("chatContextIncluded", true)
                .containsEntry("chatContextCount", 2);
    }

    @Test
    void historicalRowWithoutChatContextMetadataDefaultsToFalseAndZero() {
        CurrentUser user = new CurrentUser(10L, 1L, "steward", "管家", "EMPLOYEE", 2L,
                Set.of("STEWARD"), Set.of("ai:read", "quickreply:view"), Set.of());
        AiReplySuggestion legacy = new AiReplySuggestion(50L, 1L, 5L, 100L, 10L, "FOLLOWUP", "{}",
                "hash", "key", cipher.encrypt("林女士您好，请问最近体验如何？"), "仅CRM事实", "[]",
                "mock", "mock-reply-rules-v1", "reply-v1", new BigDecimal("0.70"), "PROPOSED",
                false, false, null, null, null, null, null, null, Instant.now(), Instant.now(), 0L);
        when(repository.findById(1L, 10L, 50L)).thenReturn(Optional.of(legacy));

        AiReplySuggestionResponse response = service.get(user, 50L);

        assertThat(response.chatContextIncluded()).isFalse();
        assertThat(response.chatContextCount()).isZero();
    }

    @Test
    void numbersMentionedOnlyInChatContextCannotBypassFactNumberValidation() {
        CurrentUser user = new CurrentUser(10L, 1L, "steward", "管家", "EMPLOYEE", 2L,
                Set.of("STEWARD"), Set.of("ai:write", "quickreply:view", "wecom:read"), Set.of());
        QuickReplyTemplate template = new QuickReplyTemplate(5L, 1L, 1L, "余次提醒", "PROJECT_REMAINING", "TEXT",
                "还剩{remaining_count}次", "PACKAGE_REMAINING", "GLOBAL", null, true, true, true, false,
                "ACTIVE", 1, 1L, Instant.now(), Instant.now(), 0L);
        when(repository.findByGenerationKey(1L, 10L, "k-num")).thenReturn(Optional.empty());
        when(previewService.requireAiTemplate(user, 5L)).thenReturn(template);
        when(factsProvider.load(1L, user, 100L)).thenReturn(Map.of("customer_name", "林女士", "remaining_count", 6));
        List<AiWorkerClient.MessageInput> recent = List.of(
                new AiWorkerClient.MessageInput("11", "CUSTOMER", Instant.parse("2026-11-22T08:00:00Z"), "我记得还有99次"));
        when(chatContextProvider.loadRecentConsentedMessages(user, 100L)).thenReturn(recent);
        when(workerClient.generateReply(anyString(), eq(100L), eq("PACKAGE_REMAINING"), anyMap(), eq(""),
                eq(recent), nullable(AiWorkerClient.ProviderConfig.class)))
                .thenReturn(new AiWorkerClient.ReplyGenerationResult("openai_compatible", "gpt-test", "reply-v1",
                        new AiWorkerClient.ReplySuggestionData("PACKAGE_REMAINING", "林女士您好，您还剩99次。",
                                "引用聊天数字", new BigDecimal("0.80"),
                                List.of("customer_name"), "UNVERIFIED", false, true)));

        assertThatThrownBy(() -> service.generate(user, new GenerateAiReplyRequest(100L, 5L, null), "k-num"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.AI_PROVIDER_FAILED));
    }
}
