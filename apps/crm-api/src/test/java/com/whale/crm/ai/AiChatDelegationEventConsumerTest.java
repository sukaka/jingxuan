package com.whale.crm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.application.AiChatDelegationEventConsumer;
import com.whale.crm.ai.application.AiChatDelegationService;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.integration.wecom.adapter.WecomAdapter;
import com.whale.crm.integration.wecom.adapter.WecomAdapterRouter;
import com.whale.crm.integration.wecom.adapter.WecomArchiveMessage;
import com.whale.crm.integration.wecom.adapter.WecomArchivePage;
import com.whale.crm.integration.wecom.api.dto.WecomSyncRequest;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.application.WecomRecallService;
import com.whale.crm.integration.wecom.application.WecomRoomAuthorization;
import com.whale.crm.integration.wecom.application.WecomRoomService;
import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomCursor;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.integration.wecom.domain.WecomRoom;
import com.whale.crm.integration.wecom.domain.WecomRoomParticipant;
import com.whale.crm.integration.wecom.media.application.WecomMediaRegistrationService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CryptoProperties;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.trading.application.OutboxRelayService;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.OutboxEvent;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatDelegationEventConsumerTest {
    @Mock AiChatDelegationService service;
    @Mock WecomRepository wecomRepository;
    @Mock JdbcTemplate jdbcTemplate;

    @Test
    void realWecomArchiveServiceDirectLongEventIdAndRoomEventAreHandledAndDeduplicated() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String directMsgId = "m".repeat(140);
        String directEventId = "wecom.message.archived.v1:corp:" + directMsgId;
        assertThat(directEventId.length()).isGreaterThan(64).isLessThanOrEqualTo(192);

        List<String> relayed = produceRealWecomEnvelopes(mapper, now, directMsgId, "room-msg-1");
        assertThat(relayed).hasSize(2);
        String directEnvelope = relayed.get(0);
        String roomEnvelope = relayed.get(1);
        assertThat(directEnvelope).contains("\"conversationType\":\"DIRECT\"", "\"roomRosterVersion\":null");
        assertThat(roomEnvelope).contains("\"conversationType\":\"ROOM\"", "\"roomRosterVersion\":0");

        AiChatDelegationEventConsumer consumer = new AiChatDelegationEventConsumer(service, wecomRepository,
                jdbcTemplate, mapper, new TestTransactionManager());

        when(jdbcTemplate.update(anyString(), eq(1L), eq(AiChatDelegationEventConsumer.GROUP), eq(directEventId)))
                .thenReturn(1).thenThrow(new DuplicateKeyException("duplicate"));
        consumer.consume(directEnvelope);
        consumer.consume(directEnvelope);
        verify(service, times(1)).receive(1L, 91L);

        String roomEventId = "wecom.message.archived.v1:corp:room-msg-1";
        when(jdbcTemplate.update(anyString(), eq(1L), eq(AiChatDelegationEventConsumer.GROUP), eq(roomEventId)))
                .thenReturn(1).thenThrow(new DuplicateKeyException("duplicate"));
        consumer.consume(roomEnvelope);
        consumer.consume(roomEnvelope);
        verify(service, never()).receive(1L, 92L);
        verify(jdbcTemplate, times(2)).update(anyString(), eq(1L),
                eq(AiChatDelegationEventConsumer.GROUP), eq(roomEventId));

        Map<String, Object> badEnvelope = mapper.readValue(directEnvelope, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> badPayload = new LinkedHashMap<>((Map<String, Object>) badEnvelope.get("payload"));
        badPayload.put("text", "untrusted");
        badEnvelope.put("payload", badPayload);
        assertThatThrownBy(() -> consumer.consume(mapper.writeValueAsString(badEnvelope)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<String> produceRealWecomEnvelopes(ObjectMapper mapper, Instant now, String directMsgId,
                                                   String roomMsgId) {
        WecomAdapterRouter router = mock(WecomAdapterRouter.class);
        WecomAdapter adapter = mock(WecomAdapter.class);
        when(router.adapter(1L)).thenReturn(adapter);
        when(adapter.name()).thenReturn("MOCK");
        when(adapter.pullContacts("corp")).thenReturn(List.of());
        WecomArchiveMessage direct = new WecomArchiveMessage(directMsgId, 1L, "external", 8L, "conv-1",
                "TEXT", "CUSTOMER", now, "你好，想咨询项目");
        WecomArchiveMessage room = WecomArchiveMessage.room(roomMsgId, 2L, "wr-room-1", "TEXT", "CUSTOMER",
                now, "群聊消息", "external", null, List.of(), null);
        when(adapter.pullArchive("corp", 0L, 50))
                .thenReturn(new WecomArchivePage(List.of(direct, room), 2L, false));

        WecomCursor cursor = new WecomCursor(1L, "corp", "MOCK_ARCHIVE", 0L, now, now, "SUCCESS",
                0, null, false, null, 0L);
        when(wecomRepository.findCursorForUpdate(1L, "corp", "MOCK_ARCHIVE")).thenReturn(Optional.of(cursor));
        when(wecomRepository.updateCursor(eq(1L), eq(1L), eq(0L), org.mockito.ArgumentMatchers.anyLong(),
                any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                any(), org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(1);
        when(wecomRepository.findMessage(1L, "corp", directMsgId)).thenReturn(Optional.empty());
        when(wecomRepository.findMessage(1L, "corp", roomMsgId)).thenReturn(Optional.empty());
        when(wecomRepository.findContact(1L, "corp", "external", 8L)).thenReturn(Optional.of(new WecomContact(
                1L, "corp", "external", 8L, null, null, null, null, null, null, null, null, null, null, null,
                "ACTIVE", 101L, "GRANTED", "evidence", "MANUAL", "ok", 1L, now, 0L)));
        when(wecomRepository.archiveMemberEnabled(1L, "corp", 8L)).thenReturn(true);

        AtomicReference<WecomMessage> persistedDirect = new AtomicReference<>();
        AtomicReference<WecomMessage> persistedRoom = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            WecomMessage m = inv.getArgument(1);
            if (directMsgId.equals(m.msgId())) {
                persistedDirect.set(new WecomMessage(91L, m.corpId(), m.msgId(), m.archiveSeq(), m.customerId(),
                        m.externalUserid(), m.staffUserId(), m.conversationId(), m.messageType(), m.senderType(),
                        m.sentAt(), m.contentObjectKey(), m.contentSha256(), m.consentSnapshot(),
                        m.consentEvidenceRef(), m.status(), m.recalledAt(), m.recallMessageId(),
                        m.currentConsentStatus(), 0L, m.conversationType(), m.senderWecomUserid(),
                        m.senderStaffUserId(), m.roomRosterVersion(), m.archiveScope()));
            } else {
                persistedRoom.set(new WecomMessage(92L, m.corpId(), m.msgId(), m.archiveSeq(), m.customerId(),
                        m.externalUserid(), m.staffUserId(), m.conversationId(), m.messageType(), m.senderType(),
                        m.sentAt(), m.contentObjectKey(), m.contentSha256(), m.consentSnapshot(),
                        m.consentEvidenceRef(), m.status(), m.recalledAt(), m.recallMessageId(),
                        m.currentConsentStatus(), 0L, m.conversationType(), m.senderWecomUserid(),
                        m.senderStaffUserId(), m.roomRosterVersion(), m.archiveScope()));
            }
            return null;
        }).when(wecomRepository).insertMessage(eq(1L), any(WecomMessage.class));
        when(wecomRepository.findMessage(1L, "corp", directMsgId))
                .thenAnswer(inv -> Optional.ofNullable(persistedDirect.get()));
        when(wecomRepository.findMessage(1L, "corp", roomMsgId))
                .thenAnswer(inv -> Optional.ofNullable(persistedRoom.get()));
        when(wecomRepository.findMessageById(1L, 91L)).thenAnswer(inv -> Optional.ofNullable(persistedDirect.get()));
        when(wecomRepository.findMessageById(1L, 92L)).thenAnswer(inv -> Optional.ofNullable(persistedRoom.get()));

        WecomRoomService roomService = mock(WecomRoomService.class);
        when(roomService.authorize(1L, "corp", "wr-room-1", "external"))
                .thenReturn(WecomRoomAuthorization.allow(
                        new WecomRoom(1L, 1L, "corp", "wr-room-1", "群", "CONFIRMED",
                                "ref", now, 1L, now, now, 0L),
                        new WecomRoomParticipant(1L, "corp", "wr-room-1", "external", "EXTERNAL",
                                null, "external", "ACTIVE", now, null, 0L)));
        WecomRecallService recallService = mock(WecomRecallService.class);
        WecomMediaRegistrationService mediaService = mock(WecomMediaRegistrationService.class);
        PrivateObjectStorage storage = mock(PrivateObjectStorage.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivateObjectStorage> storageProvider = mock(ObjectProvider.class);
        when(storageProvider.getIfAvailable()).thenReturn(storage);

        TradingRepository tradingRepo = mock(TradingRepository.class);
        List<OutboxEvent> outboxEvents = new ArrayList<>();
        when(tradingRepo.findOutboxByEventId(eq(1L), anyString())).thenAnswer(inv -> {
            String eid = inv.getArgument(1);
            return outboxEvents.stream().filter(e -> eid.equals(e.eventId())).findFirst();
        });
        org.mockito.Mockito.doAnswer(inv -> {
            OutboxEvent e = inv.getArgument(1);
            long id = outboxEvents.size() + 1L;
            outboxEvents.add(new OutboxEvent(id, e.tenantId(), e.eventId(), e.eventType(), e.eventVersion(),
                    e.aggregateType(), e.aggregateId(), e.partitionKey(), e.payloadJson(), e.status(),
                    e.retryCount(), e.sentAt(), e.lastError(), e.nextRetryAt(), e.claimOwner(),
                    e.claimExpiresAt(), e.createdAt(), e.version()));
            return null;
        }).when(tradingRepo).insertOutboxEvent(eq(1L), any(OutboxEvent.class));

        OutboxService outboxService = new OutboxService(tradingRepo, mapper);
        AesGcmCipher cipher = new AesGcmCipher(new CryptoProperties("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "pepper"));
        WecomArchiveService archiveService = new WecomArchiveService(wecomRepository, router, cipher, storageProvider,
                outboxService, mock(AuditService.class), mapper, mock(CustomerScopeSupport.class),
                mediaService, recallService, roomService, new TestTransactionManager());

        CurrentUser admin = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("wecom:sync"), Set.of());
        archiveService.sync(admin, new WecomSyncRequest("corp", 0L, 50));

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        List<String> sentJson = new ArrayList<>();
        when(tradingRepo.findOutboxTenantIds(any())).thenReturn(List.of(1L));
        when(tradingRepo.findPendingOutbox(eq(1L), any(), eq(50))).thenReturn(outboxEvents);
        AtomicReference<String> claimOwnerRef = new AtomicReference<>("relay");
        when(tradingRepo.claimOutboxEvent(eq(1L), anyLong(), eq(0L), anyString(), any(), any())).thenAnswer(inv -> {
            claimOwnerRef.set(inv.getArgument(3));
            return 1;
        });
        when(tradingRepo.findOutboxById(eq(1L), anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(1);
            OutboxEvent e = outboxEvents.get((int) id - 1);
            return Optional.of(new OutboxEvent(e.id(), e.tenantId(), e.eventId(), e.eventType(), e.eventVersion(),
                    e.aggregateType(), e.aggregateId(), e.partitionKey(), e.payloadJson(), "CLAIMED",
                    e.retryCount(), e.sentAt(), e.lastError(), e.nextRetryAt(), claimOwnerRef.get(),
                    Instant.now().plusSeconds(30), e.createdAt(), 1L));
        });
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            sentJson.add(inv.getArgument(2));
            return CompletableFuture.completedFuture(null);
        });
        when(tradingRepo.markOutboxSent(eq(1L), anyLong(), eq(1L), anyString(), any())).thenReturn(1);

        OutboxRelayService relay = new OutboxRelayService(tradingRepo, kafkaTemplate, mapper, false,
                50, 30000, 5000, 1000, 60000);
        assertThat(relay.relayOnce()).isEqualTo(2);
        return sentJson;
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
