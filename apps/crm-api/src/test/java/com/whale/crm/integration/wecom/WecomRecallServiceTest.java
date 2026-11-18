package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.adapter.WecomArchiveMessage;
import com.whale.crm.integration.wecom.application.WecomRecallService;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRecallEvent;
import com.whale.crm.integration.wecom.domain.WecomRecallRepository;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.trading.application.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomRecallServiceTest {
    private WecomRecallRepository recalls;
    private WecomRepository messages;
    private WecomRecallService service;
    private WecomArchiveMessage source;

    @BeforeEach
    void setUp() {
        recalls = mock(WecomRecallRepository.class);
        messages = mock(WecomRepository.class);
        service = new WecomRecallService(recalls, messages, mock(OutboxService.class), mock(AuditService.class));
        source = new WecomArchiveMessage("recall-1", 10, "wm-customer", 2L, "wm-customer", "REVOKE",
                "CUSTOMER", Instant.parse("2026-11-13T08:00:00Z"), "", null, null, "original-1");
    }

    @Test
    void appliedRecallReplayDoesNotTouchOriginalOrPublishAgain() {
        WecomRecallEvent event = event("APPLIED");
        when(recalls.findByRecallMessageId(1L, "corp", "recall-1")).thenReturn(Optional.of(event));

        assertThat(service.process(1L, "corp", source)).isEqualTo(WecomRecallService.RecallResult.REPLAY);

        verify(messages, never()).markMessageRecalled(anyLong(), anyString(), anyString(), anyLong(), any(), anyString());
        verify(recalls, never()).markApplied(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void differentSenderCannotRecallAnotherConversationMessage() {
        WecomRecallEvent event = event("PENDING_ORIGINAL");
        when(recalls.findByRecallMessageId(1L, "corp", "recall-1")).thenReturn(Optional.empty());
        when(recalls.findByRecallMessageId(1L, "corp", "recall-1")).thenReturn(Optional.empty(), Optional.of(event));
        WecomMessage original = new WecomMessage(8L, "corp", "original-1", 9L, 100L, "wm-other", 2L,
                "wm-other", "TEXT", "CUSTOMER", Instant.parse("2026-11-13T07:00:00Z"), "object", "hash",
                "GRANTED", "evidence", "STORED", "GRANTED", 0L);
        when(messages.findMessage(1L, "corp", "original-1")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.process(1L, "corp", source)).hasMessageContaining("不一致");
        verify(messages, never()).markMessageRecalled(anyLong(), anyString(), anyString(), anyLong(), any(), anyString());
        verify(recalls, never()).markApplied(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void roomRecallMustMatchRoomAndActualSender() {
        source = WecomArchiveMessage.room("recall-room", 11, "wr-room", "REVOKE", "CUSTOMER",
                Instant.parse("2026-11-13T08:00:00Z"), "", "wm-one", null, List.of(), "original-room");
        WecomRecallEvent event = new WecomRecallEvent(1L, 1L, "corp", "recall-room", "original-room", 11,
                null, null, "CUSTOMER", source.sentAt(), "PENDING_ORIGINAL", null, Instant.now(), null, 0,
                "ROOM", "wr-room", "wm-one");
        when(recalls.findByRecallMessageId(1L, "corp", "recall-room")).thenReturn(Optional.empty(), Optional.of(event));
        WecomMessage original = new WecomMessage(9L, "corp", "original-room", 9L, null, null, null,
                "wr-other", "TEXT", "CUSTOMER", Instant.parse("2026-11-13T07:00:00Z"), "object", "hash",
                "GRANTED", null, "STORED", null, null, "GRANTED", 0L,
                "ROOM", "wm-one", null, 0L);
        when(messages.findMessage(1L, "corp", "original-room")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.process(1L, "corp", source)).hasMessageContaining("实际发送方");
        verify(messages, never()).markMessageRecalled(anyLong(), anyString(), anyString(), anyLong(), any(), anyString());
    }

    private WecomRecallEvent event(String status) {
        return new WecomRecallEvent(1L, 1L, "corp", "recall-1", "original-1", 10,
                "wm-customer", 2L, "CUSTOMER", source.sentAt(), status, 8L, Instant.now(), null, 0);
    }
}
