package com.whale.crm.ai;

import com.whale.crm.ai.application.AiReplyChatContextProvider;
import com.whale.crm.ai.application.AiWorkerClient;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiReplyChatContextProviderTest {
    @Mock
    private WecomRepository wecomRepository;
    @Mock
    private WecomArchiveService archiveService;
    @Mock
    private CustomerScopeSupport scopeSupport;

    private AiReplyChatContextProvider provider;

    @BeforeEach
    void setUp() {
        provider = new AiReplyChatContextProvider(wecomRepository, archiveService, scopeSupport,
                new QuickReplyTextMasker());
    }

    @Test
    void returnsEmptyWithoutQueryingWecomWhenUserLacksWecomReadPermission() {
        CurrentUser user = new CurrentUser(10L, 1L, "steward", "管家", "EMPLOYEE", 2L,
                Set.of("STEWARD"), Set.of("ai:write", "quickreply:view"), Set.of());

        List<AiWorkerClient.MessageInput> result = provider.loadRecentConsentedMessages(user, 100L);

        assertThat(result).isEmpty();
        verifyNoInteractions(wecomRepository, archiveService, scopeSupport);
    }

    @Test
    void loadsLatestTenConsentedNonRecalledTextMessagesInChronologicalOrderAndMasksSensitiveText() {
        CurrentUser user = wecomUser();
        stubScope(user, "SELF", List.of(), 10L);
        Instant base = Instant.parse("2026-11-22T08:00:00Z");

        // Returned newest-first like WecomMapper.selectMessages (sent_at DESC, id DESC)
        List<WecomMessage> candidates = new ArrayList<>();
        // Excluded messages interleaved with eligible ones
        candidates.add(message(100L, 55L, "IMAGE", "CUSTOMER", base.plusSeconds(200), null, "GRANTED", "GRANTED", "STORED", "DIRECT"));
        candidates.add(message(101L, 55L, "TEXT", "CUSTOMER", base.plusSeconds(199), base.plusSeconds(201), "GRANTED", "GRANTED", "STORED", "DIRECT"));
        candidates.add(message(102L, 55L, "TEXT", "CUSTOMER", base.plusSeconds(198), null, "GRANTED", "REVOKED", "STORED", "DIRECT"));
        candidates.add(message(103L, 55L, "TEXT", "CUSTOMER", base.plusSeconds(197), null, "GRANTED", "GRANTED", "STORED", "ROOM"));
        candidates.add(message(104L, 99L, "TEXT", "CUSTOMER", base.plusSeconds(196), null, "GRANTED", "GRANTED", "STORED", "DIRECT"));
        // 12 eligible messages (ids 20..31, newest first) -> should pick only latest 10 (ids 22..31)
        for (int i = 12; i >= 1; i--) {
            candidates.add(message(19L + i, 55L, "TEXT", i % 2 == 0 ? "CUSTOMER" : "EMPLOYEE",
                    base.plusSeconds(i * 10L), null, "GRANTED", "GRANTED", "STORED", "DIRECT"));
        }

        when(wecomRepository.listMessages(eq(1L), eq(55L), isNull(), isNull(), eq("SELF"), eq(List.of()),
                eq(10L), eq(0), eq(50))).thenReturn(candidates);
        when(wecomRepository.findContact(1L, "CORP-1", "ext-55", 10L))
                .thenReturn(Optional.of(contact(55L, "GRANTED", "ACTIVE")));
        when(wecomRepository.archiveMemberEnabled(1L, "CORP-1", 10L)).thenReturn(true);
        when(archiveService.readContent(any(WecomMessage.class))).thenAnswer(invocation -> {
            WecomMessage msg = invocation.getArgument(0);
            if (msg.id() == 31L) {
                return "你好\n我的手机13800138000，邮箱test@example.com，备注" + "A".repeat(400);
            }
            return "消息内容-" + msg.id();
        });

        List<AiWorkerClient.MessageInput> result = provider.loadRecentConsentedMessages(user, 55L);

        assertThat(result).hasSize(10);
        // Chronological order: oldest (id 22) to newest (id 31)
        assertThat(result).extracting(AiWorkerClient.MessageInput::id)
                .containsExactly("22", "23", "24", "25", "26", "27", "28", "29", "30", "31");
        AiWorkerClient.MessageInput latest = result.getLast();
        assertThat(latest.text())
                .contains("138****8000")
                .contains("***@***")
                .doesNotContain("13800138000", "test@example.com", "\n")
                .endsWith("...");
        assertThat(latest.text().length()).isLessThanOrEqualTo(300);
    }

    @Test
    void neverReadsMessageContentWhenLinkedContactConsentIsNotGrantedOrMemberDisabled() {
        CurrentUser user = wecomUser();
        stubScope(user, "TEAM", List.of(2L), 10L);
        WecomMessage msg = message(1L, 55L, "TEXT", "CUSTOMER", Instant.parse("2026-11-22T08:00:00Z"),
                null, "GRANTED", "GRANTED", "STORED", "DIRECT");
        when(wecomRepository.listMessages(eq(1L), eq(55L), isNull(), isNull(), eq("TEAM"), eq(List.of(2L)),
                eq(10L), eq(0), eq(50))).thenReturn(List.of(msg));

        // Case 1: contact consent PENDING
        when(wecomRepository.findContact(1L, "CORP-1", "ext-55", 10L))
                .thenReturn(Optional.of(contact(55L, "PENDING", "ACTIVE")));
        assertThat(provider.loadRecentConsentedMessages(user, 55L)).isEmpty();
        verify(archiveService, never()).readContent(any());

        // Case 2: contact consent REVOKED
        when(wecomRepository.findContact(1L, "CORP-1", "ext-55", 10L))
                .thenReturn(Optional.of(contact(55L, "REVOKED", "ACTIVE")));
        assertThat(provider.loadRecentConsentedMessages(user, 55L)).isEmpty();
        verify(archiveService, never()).readContent(any());

        // Case 3: contact GRANTED but archive member disabled
        when(wecomRepository.findContact(1L, "CORP-1", "ext-55", 10L))
                .thenReturn(Optional.of(contact(55L, "GRANTED", "ACTIVE")));
        when(wecomRepository.archiveMemberEnabled(1L, "CORP-1", 10L)).thenReturn(false);
        assertThat(provider.loadRecentConsentedMessages(user, 55L)).isEmpty();
        verify(archiveService, never()).readContent(any());

        // Case 4: contact GRANTED but unlinked/remapped to another customer
        when(wecomRepository.findContact(1L, "CORP-1", "ext-55", 10L))
                .thenReturn(Optional.of(contact(77L, "GRANTED", "ACTIVE")));
        assertThat(provider.loadRecentConsentedMessages(user, 55L)).isEmpty();
        verify(archiveService, never()).readContent(any());
    }

    @Test
    void fallsBackGracefullyWhenPrivateObjectStorageIsUnavailableOrSingleMessageUnreadable() {
        CurrentUser user = wecomUser();
        stubScope(user, "ALL", List.of(), 10L);
        Instant now = Instant.parse("2026-11-22T09:00:00Z");
        WecomMessage first = message(1L, 55L, "TEXT", "CUSTOMER", now.plusSeconds(10),
                null, "GRANTED", "GRANTED", "STORED", "DIRECT");
        WecomMessage second = message(2L, 55L, "TEXT", "EMPLOYEE", now,
                null, "GRANTED", "GRANTED", "STORED", "DIRECT");
        when(wecomRepository.listMessages(anyLong(), eq(55L), isNull(), isNull(), anyString(), anyList(),
                anyLong(), anyInt(), anyInt())).thenReturn(List.of(first, second));
        when(wecomRepository.findContact(1L, "CORP-1", "ext-55", 10L))
                .thenReturn(Optional.of(contact(55L, "GRANTED", "ACTIVE")));
        when(wecomRepository.archiveMemberEnabled(1L, "CORP-1", 10L)).thenReturn(true);

        // Storage unavailable -> stops cleanly and returns empty
        doThrow(new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "私有对象存储未配置"))
                .when(archiveService).readContent(first);
        assertThat(provider.loadRecentConsentedMessages(user, 55L)).isEmpty();
        verify(archiveService, never()).readContent(second);

        // Single corrupted object -> skips corrupted one and returns readable message
        doThrow(new BusinessException(ErrorCode.CONFLICT, "归档对象校验失败"))
                .when(archiveService).readContent(first);
        doReturn("您好，请问方便安排周六到院吗？").when(archiveService).readContent(second);
        List<AiWorkerClient.MessageInput> partial = provider.loadRecentConsentedMessages(user, 55L);
        assertThat(partial).hasSize(1);
        assertThat(partial.getFirst().id()).isEqualTo("2");
        assertThat(partial.getFirst().text()).isEqualTo("您好，请问方便安排周六到院吗？");
    }

    private CurrentUser wecomUser() {
        return new CurrentUser(10L, 1L, "steward", "管家", "EMPLOYEE", 2L,
                Set.of("STEWARD"), Set.of("ai:write", "quickreply:view", "wecom:read"), Set.of());
    }

    private void stubScope(CurrentUser user, String scopeType, List<Long> teamIds, long selfId) {
        DataScope scope = DataScope.self();
        when(scopeSupport.scope(user)).thenReturn(scope);
        when(scopeSupport.queryOf(scope, user)).thenReturn(new CustomerScopeSupport.ScopeQuery(scopeType, teamIds, selfId));
    }

    private WecomMessage message(long id, long customerId, String type, String senderType, Instant sentAt,
                                 Instant recalledAt, String consentSnapshot, String currentConsentStatus,
                                 String status, String conversationType) {
        return new WecomMessage(id, "CORP-1", "msg-" + id, id, customerId, "ext-55", 10L, "conv-55",
                type, senderType, sentAt, "wecom/1/CORP-1/" + id + ".enc", "a".repeat(64),
                consentSnapshot, "evidence-1", status, recalledAt, null, currentConsentStatus, 0L,
                conversationType, null, null, null, "MOCK_ARCHIVE");
    }

    private WecomContact contact(long customerId, String consentStatus, String followStatus) {
        return new WecomContact(1L, "CORP-1", "ext-55", 10L, "测试客户", null, 1, 2, null,
                null, null, null, null, null, null, followStatus, customerId, consentStatus,
                "evidence-1", "MANUAL", "人工绑定", 1L, Instant.now(), 0L);
    }
}
