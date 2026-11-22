package com.whale.crm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.application.AiChatDelegationSafety;
import com.whale.crm.ai.application.AiChatDelegationService;
import com.whale.crm.ai.application.AiChatSendFenceService;
import com.whale.crm.ai.application.AiProviderConfigService;
import com.whale.crm.ai.application.AiWorkerClient;
import com.whale.crm.ai.domain.AiChatDelegationAttempt;
import com.whale.crm.ai.domain.AiChatDelegationPolicy;
import com.whale.crm.ai.domain.AiChatDelegationRepository;
import com.whale.crm.ai.domain.AiChatDelegationRun;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.integration.wecom.adapter.ExternalContactMessageSender;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.notification.domain.WecomSendResult;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatDelegationServiceTest {
    @Mock AiChatDelegationRepository repository;
    @Mock WecomRepository wecomRepository;
    @Mock WecomArchiveService archiveService;
    @Mock CustomerRepository customerRepository;
    @Mock UserRepository userRepository;
    @Mock AiWorkerClient workerClient;
    @Mock AiProviderConfigService providerConfigService;
    @Mock AiChatSendFenceService sendFenceService;
    @Mock ExternalContactMessageSender sender;
    @Mock AuditService auditService;
    @Mock AesGcmCipher cipher;
    private AiChatDelegationService service;
    private final Instant now = Instant.parse("2026-01-01T01:00:00Z");

    @BeforeEach
    void setUp() {
        service = new AiChatDelegationService(repository, wecomRepository, archiveService, customerRepository,
                userRepository, workerClient, providerConfigService, new AiChatDelegationSafety(), sendFenceService,
                sender, auditService, cipher, new ObjectMapper().findAndRegisterModules(), new TestTransactionManager(),
                Clock.fixed(now, ZoneOffset.UTC));
        lenient().when(repository.findRun(1L, 11L)).thenReturn(Optional.of(run("SENDING")));
        lenient().when(repository.findRunForUpdate(1L, 11L)).thenReturn(Optional.of(run("SENDING")));
        lenient().when(customerRepository.findByIdForUpdate(1L, 101L)).thenReturn(Optional.of(customer()));
        lenient().when(repository.findPolicy(1L)).thenReturn(Optional.of(policy(false)));
        lenient().when(repository.findPolicyForUpdate(1L)).thenReturn(Optional.of(policy(false)));
        lenient().when(repository.findCustomerControl(1L, 101L)).thenReturn(Optional.empty());
        lenient().when(wecomRepository.findMessageById(1L, 91L)).thenReturn(Optional.of(message()));
        lenient().when(wecomRepository.archiveMemberEnabled(1L, "corp", 8L)).thenReturn(true);
        lenient().when(wecomRepository.findContactForUpdate(1L, "corp", "external", 8L)).thenReturn(Optional.of(contact()));
        lenient().when(userRepository.findById(1L, 8L)).thenReturn(Optional.of(activeStaff()));
        lenient().when(repository.findLatestSendAttempt(1L, 101L)).thenReturn(Optional.empty());
        lenient().when(repository.countSendAttemptsSince(eq(1L), eq(101L), any())).thenReturn(0L);
        lenient().when(repository.markSendAttempted(1L, 11L, 3L, "worker-a", now)).thenReturn(1);
        lenient().when(cipher.decrypt("encrypted-reply")).thenReturn("正常回复");
        lenient().when(sendFenceService.authorize(any(), anyString(), any())).thenAnswer(invocation -> {
            AiChatDelegationRun candidate = invocation.getArgument(0);
            return new AiChatSendFenceService.Decision(
                    new AiChatSendFenceService.Authorization(candidate.tenantId(), candidate.id(), 0), null);
        });
    }

    @Test
    void unknownSendIsTerminalAndNeverAutomaticallyRetried() {
        when(sender.send(any())).thenReturn(WecomSendResult.unknown("OFFICIAL", "WECOM_RESPONSE_UNKNOWN"));
        when(repository.finishSend(eq(1L), eq(11L), eq(3L), eq("worker-a"), eq("UNKNOWN"),
                eq("WECOM_RESPONSE_UNKNOWN"), eq("OFFICIAL"), isNull(), isNull(), any())).thenReturn(1);

        service.send(1L, 11L, "worker-a");

        verify(repository).finishSend(eq(1L), eq(11L), eq(3L), eq("worker-a"), eq("UNKNOWN"),
                eq("WECOM_RESPONSE_UNKNOWN"), eq("OFFICIAL"), isNull(), isNull(), any());
        ArgumentCaptor<AiChatDelegationAttempt> attempt = ArgumentCaptor.forClass(AiChatDelegationAttempt.class);
        verify(repository).insertAttempt(attempt.capture());
        assertThat(attempt.getValue().result()).isEqualTo("UNKNOWN");
        verify(auditService).record(eq(1L), isNull(), eq("AI_CHAT_DELEGATION_SEND_UNKNOWN"),
                eq("AI_CHAT_DELEGATION_RUN"), eq("11"), any(), any(), isNull());
    }

    @Test
    void acceptedOfficialResultActuallyUsesExternalContactSenderAndPersistsProviderReference() {
        when(sender.send(any())).thenReturn(new WecomSendResult(WecomSendResult.Status.SENT,
                "OFFICIAL", "official-message-1", null));
        when(repository.finishSend(eq(1L), eq(11L), eq(3L), eq("worker-a"), eq("SENT"),
                isNull(), eq("OFFICIAL"), eq("official-message-1"), isNull(), any())).thenReturn(1);

        service.send(1L, 11L, "worker-a");

        ArgumentCaptor<com.whale.crm.integration.wecom.adapter.ExternalContactMessageCommand> command =
                ArgumentCaptor.forClass(com.whale.crm.integration.wecom.adapter.ExternalContactMessageCommand.class);
        verify(sender).send(command.capture());
        assertThat(command.getValue().externalUserid()).isEqualTo("external");
        assertThat(command.getValue().text()).isEqualTo("正常回复");
        verify(repository).finishSend(eq(1L), eq(11L), eq(3L), eq("worker-a"), eq("SENT"),
                isNull(), eq("OFFICIAL"), eq("official-message-1"), isNull(), any());
    }

    @Test
    void appointmentFactClaimStopsGenerateToSendPipelineWithZeroSenderCall() {
        when(repository.findRunForUpdate(1L, 11L)).thenReturn(Optional.of(run("GENERATING")));
        when(providerConfigService.workerEnabled(1L)).thenReturn(true);
        when(archiveService.readContent(any())).thenReturn("我想预约");
        AiWorkerClient.InsightData insight = new AiWorkerClient.InsightData("APPOINTMENT_INTENT", List.of(),
                null, null, null, List.of("91"), new java.math.BigDecimal("0.80"), List.of(), "UNVERIFIED",
                false, "预约意向待核实", "已为您预约成功", null, List.of());
        when(workerClient.extract(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new AiWorkerClient.ExtractionResult("mock", "mock-v1", insight));
        when(repository.updateRunStatus(eq(1L), eq(11L), eq(3L), eq("GENERATING"), eq("SKIPPED"),
                eq("AI_BUSINESS_FACT_CLAIM_BLOCKED"), isNull(), any())).thenReturn(1);

        service.generate(1L, 11L, "worker-a");

        verify(repository, never()).markGenerated(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any());
        verify(sender, never()).send(any());
    }

    @Test
    void appointmentFactClaimInEncryptedReplyIsAlsoBlockedAtSendGate() {
        when(cipher.decrypt("encrypted-reply")).thenReturn("预约已确认");
        when(repository.updateRunStatus(eq(1L), eq(11L), eq(3L), eq("SENDING"), eq("SKIPPED"),
                eq("AI_BUSINESS_FACT_CLAIM_BLOCKED"), isNull(), any())).thenReturn(1);

        service.send(1L, 11L, "worker-a");

        verify(sender, never()).send(any());
        verify(sendFenceService, never()).authorize(any(), anyString(), any());
    }

    @Test
    void authorizationRevokedAfterPrepareStillPreventsSenderInvocation() {
        org.mockito.Mockito.doReturn(new AiChatSendFenceService.Decision(null, "HUMAN_TAKEOVER"))
                .when(sendFenceService).authorize(any(), anyString(), any());
        when(repository.updateRunStatus(eq(1L), eq(11L), eq(3L), eq("SENDING"), eq("SKIPPED"),
                eq("HUMAN_TAKEOVER"), isNull(), any())).thenReturn(1);

        service.send(1L, 11L, "worker-a");

        verify(sender, never()).send(any());
        verify(repository, never()).finishSend(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                any(), anyString(), any(), any(), any());
    }

    @Test
    void emergencyStopIsRecheckedImmediatelyBeforeSending() {
        when(repository.findPolicyForUpdate(1L)).thenReturn(Optional.of(policy(true)));
        when(repository.updateRunStatus(eq(1L), eq(11L), eq(3L), eq("SENDING"), eq("SKIPPED"),
                eq("EMERGENCY_STOP"), isNull(), any())).thenReturn(1);

        service.send(1L, 11L, "worker-a");

        verify(sender, never()).send(any());
        verify(repository).updateRunStatus(eq(1L), eq(11L), eq(3L), eq("SENDING"), eq("SKIPPED"),
                eq("EMERGENCY_STOP"), isNull(), any());
    }

    @Test
    void revokedCurrentConsentBlocksSendingEvenWhenSnapshotWasGranted() {
        WecomMessage revoked = new WecomMessage(91L, "corp", "msg-91", 1L, 101L, "external", 8L,
                "conversation", "TEXT", "CUSTOMER", now, "object", "hash", "GRANTED", "evidence",
                "STORED", "REVOKED", 0L);
        when(wecomRepository.findMessageById(1L, 91L)).thenReturn(Optional.of(revoked));
        when(repository.updateRunStatus(eq(1L), eq(11L), eq(3L), eq("SENDING"), eq("SKIPPED"),
                eq("CONSENT_NOT_GRANTED"), isNull(), any())).thenReturn(1);

        service.send(1L, 11L, "worker-a");

        verify(sender, never()).send(any());
        verify(repository, never()).markSendAttempted(anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void minimumIntervalDefersSendWithoutCallingExternalContactSender() {
        when(repository.findLatestSendAttempt(1L, 101L)).thenReturn(Optional.of(now.minusSeconds(30)));
        when(repository.updateRunStatus(eq(1L), eq(11L), eq(3L), eq("SENDING"), eq("READY_TO_SEND"),
                eq("MIN_INTERVAL_NOT_REACHED"), eq(now.plusSeconds(30)), any())).thenReturn(1);

        service.send(1L, 11L, "worker-a");

        verify(sender, never()).send(any());
        verify(repository, never()).markSendAttempted(anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void outsideAllowedHoursWaitsForNextOpening() {
        AiChatDelegationPolicy restricted = new AiChatDelegationPolicy(1L, true, false, 60, 10,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "Asia/Shanghai", List.of(), 2,
                policy(false).scopes());
        when(repository.findPolicyForUpdate(1L)).thenReturn(Optional.of(restricted));
        when(repository.updateRunStatus(eq(1L), eq(11L), eq(3L), eq("SENDING"), eq("READY_TO_SEND"),
                eq("OUTSIDE_ALLOWED_HOURS"), eq(Instant.parse("2026-01-01T02:00:00Z")), any())).thenReturn(1);

        service.send(1L, 11L, "worker-a");

        verify(sender, never()).send(any());
        verify(repository, never()).markSendAttempted(anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void eligibleCustomerMessageOnlyCreatesQueuedRunWithoutSynchronouslyCallingAiOrSender() {
        AiChatDelegationRun queued = new AiChatDelegationRun(11L, 1L, 91L, 101L, 8L, 3L,
                "corp", "external", "conversation", "QUEUED", null, null, null, null,
                null, null, null, null, null, 0, 0, now, null, null, now, now, 0L);
        when(repository.findRunByMessage(1L, 91L)).thenReturn(Optional.empty(), Optional.of(queued));
        when(customerRepository.findById(1L, 101L)).thenReturn(Optional.of(customer()));

        service.receive(1L, 91L);

        ArgumentCaptor<AiChatDelegationRun> inserted = ArgumentCaptor.forClass(AiChatDelegationRun.class);
        verify(repository).insertRun(inserted.capture());
        assertThat(inserted.getValue().status()).isEqualTo("QUEUED");
        assertThat(inserted.getValue().nextAttemptAt()).isEqualTo(now);
        verify(archiveService, never()).readContent(any());
        verify(sender, never()).send(any());
    }

    @Test
    void duplicateSourceMessageDoesNotCreateAnotherRun() {
        when(repository.findRunByMessage(1L, 91L)).thenReturn(Optional.of(run("SENT")));

        service.receive(1L, 91L);

        verify(repository, never()).insertRun(any());
        verify(workerClient, never()).extract(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void retryableSendReleasesFenceForRetryWhileSentAndTerminalFinishSealFence() {
        when(sender.send(any())).thenReturn(
                new WecomSendResult(WecomSendResult.Status.RETRYABLE, "OFFICIAL", null, "TRANSIENT_429"),
                new WecomSendResult(WecomSendResult.Status.SENT, "OFFICIAL", "msg-ok", null));
        when(repository.finishSend(eq(1L), eq(11L), eq(3L), eq("worker-a"), eq("RETRY_SEND"),
                eq("TRANSIENT_429"), eq("OFFICIAL"), isNull(), any(), any())).thenReturn(1);
        when(repository.finishSend(eq(1L), eq(11L), eq(3L), eq("worker-a"), eq("SENT"),
                isNull(), eq("OFFICIAL"), eq("msg-ok"), isNull(), any())).thenReturn(1);

        service.send(1L, 11L, "worker-a");
        verify(sendFenceService).releaseForRetry(any());
        verify(sendFenceService, never()).finish(any());

        service.send(1L, 11L, "worker-a");
        verify(sendFenceService).finish(any());
    }

    @Test
    void receiveSkipsRoomMessageWithoutInsertingNotNullDelegationRun() {
        WecomMessage room = new WecomMessage(92L, "corp", "room-msg-1", 2L, null, "sender-ext", null,
                "wr-room-1", "TEXT", "CUSTOMER", now, "obj-room", "hash-room", "GRANTED", "consent-ref",
                "STORED", null, null, "GRANTED", 0L, "ROOM", "sender-ext", null, 0L, "MOCK_ARCHIVE");
        when(repository.findRunByMessage(1L, 92L)).thenReturn(Optional.empty());
        when(wecomRepository.findMessageById(1L, 92L)).thenReturn(Optional.of(room));

        assertThat(service.receive(1L, 92L)).isNull();
        verify(repository, never()).insertRun(any());
    }

    private AiChatDelegationPolicy policy(boolean emergencyStopped) {
        return new AiChatDelegationPolicy(1L, true, emergencyStopped, 60, 10,
                LocalTime.of(0, 0), LocalTime.of(23, 59), "Asia/Shanghai", List.of(), 2,
                List.of(new AiChatDelegationPolicy.ScopeOverride("TEAM", 3, true),
                        new AiChatDelegationPolicy.ScopeOverride("STAFF", 8, true)));
    }

    private AiChatDelegationRun run(String status) {
        return new AiChatDelegationRun(11L, 1L, 91L, 101L, 8L, 3L, "corp", "external", "conversation",
                status, null, "encrypted-reply", "GENERAL", "[]", "mock", "mock-v1", null, null,
                now, 1, 1, null, "worker-a", now.plusSeconds(30), now, now, 3L);
    }

    private WecomMessage message() {
        return new WecomMessage(91L, "corp", "msg-91", 1L, 101L, "external", 8L, "conversation",
                "TEXT", "CUSTOMER", now, "object", "hash", "GRANTED", "evidence", "STORED", "GRANTED", 0L);
    }

    private WecomContact contact() {
        return new WecomContact(1L, "corp", "external", 8L, null, null, null, null, null,
                null, null, null, null, null, null, "ACTIVE", 101L, "GRANTED", "evidence",
                "MANUAL", "synthetic", 1L, now, 0L);
    }

    private Customer customer() {
        Customer customer = new Customer();
        customer.setId(101L);
        customer.setTenantId(1L);
        customer.setOwnerStaffId(8L);
        customer.setTeamId(3L);
        customer.setDeleted(false);
        return customer;
    }

    private SysUser activeStaff() {
        SysUser user = new SysUser();
        user.setId(8L);
        user.setTenantId(1L);
        user.setTeamId(3L);
        user.setStatus("ACTIVE");
        user.setUserType("EMPLOYEE");
        return user;
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
