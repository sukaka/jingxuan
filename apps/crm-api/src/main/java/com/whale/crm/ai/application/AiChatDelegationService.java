package com.whale.crm.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.domain.AiChatDelegationAttempt;
import com.whale.crm.ai.domain.AiChatDelegationCustomerControl;
import com.whale.crm.ai.domain.AiChatDelegationPolicy;
import com.whale.crm.ai.domain.AiChatDelegationRepository;
import com.whale.crm.ai.domain.AiChatDelegationRun;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.integration.wecom.adapter.ExternalContactMessageCommand;
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
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class AiChatDelegationService {
    private static final int MAX_GENERATION_ATTEMPTS = 5;
    private static final int MAX_SEND_ATTEMPTS = 5;

    private final AiChatDelegationRepository repository;
    private final WecomRepository wecomRepository;
    private final WecomArchiveService archiveService;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final AiWorkerClient workerClient;
    private final AiProviderConfigService providerConfigService;
    private final AiChatDelegationSafety safety;
    private final AiChatSendFenceService sendFenceService;
    private final ExternalContactMessageSender sender;
    private final AuditService auditService;
    private final AesGcmCipher cipher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public AiChatDelegationService(AiChatDelegationRepository repository, WecomRepository wecomRepository,
                                   WecomArchiveService archiveService, CustomerRepository customerRepository,
                                   UserRepository userRepository, AiWorkerClient workerClient,
                                   AiProviderConfigService providerConfigService, AiChatDelegationSafety safety,
                                   AiChatSendFenceService sendFenceService,
                                   @org.springframework.beans.factory.annotation.Qualifier("wecomExternalContactSenderRouter")
                                   ExternalContactMessageSender sender, AuditService auditService,
                                   AesGcmCipher cipher, ObjectMapper objectMapper,
                                   PlatformTransactionManager transactionManager) {
        this(repository, wecomRepository, archiveService, customerRepository, userRepository, workerClient,
                providerConfigService, safety, sendFenceService, sender, auditService, cipher, objectMapper,
                transactionManager, Clock.systemUTC());
    }

    public AiChatDelegationService(AiChatDelegationRepository repository, WecomRepository wecomRepository,
                                   WecomArchiveService archiveService, CustomerRepository customerRepository,
                                   UserRepository userRepository, AiWorkerClient workerClient,
                                   AiProviderConfigService providerConfigService, AiChatDelegationSafety safety,
                                   AiChatSendFenceService sendFenceService, ExternalContactMessageSender sender,
                                   AuditService auditService, AesGcmCipher cipher, ObjectMapper objectMapper,
                                   PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.wecomRepository = wecomRepository;
        this.archiveService = archiveService;
        this.customerRepository = customerRepository;
        this.userRepository = userRepository;
        this.workerClient = workerClient;
        this.providerConfigService = providerConfigService;
        this.safety = safety;
        this.sendFenceService = sendFenceService;
        this.sender = sender;
        this.auditService = auditService;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional
    public AiChatDelegationRun receive(long tenantId, long sourceMessageId) {
        Optional<AiChatDelegationRun> existing = repository.findRunByMessage(tenantId, sourceMessageId);
        if (existing.isPresent()) {
            return existing.get();
        }
        WecomMessage message = wecomRepository.findMessageById(tenantId, sourceMessageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "企微来源消息不存在"));
        Customer customer = message.customerId() == null ? null
                : customerRepository.findById(tenantId, message.customerId()).orElse(null);
        Long teamId = customer == null ? null : customer.getTeamId();
        String reason = sourceBlockReason(tenantId, message, customer);
        if (reason == null) {
            reason = policyBlockReason(tenantId, customer.getId(), teamId, message.staffUserId());
        }
        Instant now = clock.instant();
        AiChatDelegationRun input = new AiChatDelegationRun(null, tenantId, sourceMessageId, message.customerId(),
                message.staffUserId(), teamId, message.corpId(), message.externalUserid(), message.conversationId(),
                reason == null ? "QUEUED" : "SKIPPED", reason, null, null, null, null, null,
                null, null, null, 0, 0, reason == null ? now : null, null, null, now, now, 0);
        repository.insertRun(input);
        AiChatDelegationRun created = repository.findRunByMessage(tenantId, sourceMessageId).orElseThrow();
        repository.insertAttempt(attempt(created, "RECEIVE", created.status(), null, null, reason));
        auditService.record(tenantId, null, "AI_CHAT_DELEGATION_RECEIVE", "AI_CHAT_DELEGATION_RUN",
                created.id().toString(), null, auditSnapshot(created.status(), reason, null), null);
        return created;
    }

    public void generate(long tenantId, long runId, String owner) {
        GenerationPreparation preparation = prepareGeneration(tenantId, runId, owner);
        if (preparation == null) {
            return;
        }
        AiChatDelegationRun run = preparation.run();
        try {
            WecomMessage message = wecomRepository.findMessageById(tenantId, run.sourceMessageId()).orElseThrow();
            String text = archiveService.readContent(message);
            if (text == null || text.isBlank() || text.length() > 10000) {
                throw new AiWorkerException("AI_SOURCE_TEXT_INVALID", true);
            }
            AiWorkerClient.ExtractionResult result = workerClient.extract(run.id(), run.customerId(),
                    List.of(new AiWorkerClient.MessageInput(Long.toString(message.id()), message.senderType(),
                            message.sentAt(), text)), List.of(), List.of(),
                    providerConfigService.workerConfig(tenantId));
            if (result == null) {
                throw new AiWorkerException("AI_OUTPUT_INVALID", true);
            }
            Optional<String> blocked = safety.generationBlockReason(result.insight(),
                    Long.toString(message.id()), preparation.policy().blockedTerms());
            if (blocked.isPresent()) {
                finishGenerationGate(run, blocked.get());
                return;
            }
            String reply = result.insight().replyDraft().strip();
            boolean saved = Boolean.TRUE.equals(transactions.execute(status -> {
                AiChatDelegationRun current = ownedRunForUpdate(tenantId, runId, "GENERATING", owner);
                if (repository.markGenerated(tenantId, runId, current.version(), owner, cipher.encrypt(reply),
                        result.insight().insightType(), json(result.insight().riskFlags()), result.provider(),
                        result.model(), clock.instant()) != 1) {
                    return false;
                }
                repository.insertAttempt(attempt(current, "GENERATE", "READY_TO_SEND", result.provider(),
                        null, null));
                auditService.record(tenantId, null, "AI_CHAT_DELEGATION_GENERATE", "AI_CHAT_DELEGATION_RUN",
                        Long.toString(runId), null, auditSnapshot("READY_TO_SEND", null, result.provider()), null);
                return true;
            }));
            if (!saved) {
                return;
            }
        } catch (AiWorkerException ex) {
            failGeneration(tenantId, runId, owner, ex.terminal(), ex.getMessage());
        } catch (BusinessException ex) {
            failGeneration(tenantId, runId, owner, true, "AI_SOURCE_NOT_READABLE");
        } catch (Exception ex) {
            failGeneration(tenantId, runId, owner, false, "AI_GENERATION_UNAVAILABLE");
        }
    }

    public void send(long tenantId, long runId, String owner) {
        SendPreparation preparation = prepareSend(tenantId, runId, owner);
        if (preparation == null) {
            return;
        }
        AiChatSendFenceService.Decision decision = sendFenceService.authorize(preparation.run(), owner,
                (policy, customer) -> {
                    String reason = complianceBlockReason(tenantId, preparation.run(), customer);
                    if (reason == null) {
                        reason = policyBlockReason(policy, preparation.run());
                    }
                    if (reason == null && !withinAllowedHours(clock.instant(), policy)) {
                        reason = "OUTSIDE_ALLOWED_HOURS";
                    }
                    if (reason == null) {
                        reason = safety.replyBlockReason(cipher.decrypt(preparation.run().replyCipher()),
                                policy.blockedTerms()).orElse(null);
                    }
                    return reason;
                });
        if (!decision.accepted()) {
            if ("SEND_ALREADY_IN_FLIGHT".equals(decision.deniedReason())
                    || "SEND_ALREADY_COMPLETED".equals(decision.deniedReason())) {
                return;
            }
            transactions.executeWithoutResult(status -> {
                AiChatDelegationRun current = ownedRunForUpdate(tenantId, runId, "SENDING", owner);
                transitionGate(current, "SENDING", "SKIPPED", decision.deniedReason(), null, "COMPLIANCE");
            });
            return;
        }
        try {
            WecomSendResult result;
            try {
                result = sender.send(preparation.command());
                if (result == null || result.status() == null) {
                    result = WecomSendResult.unknown(sender.mode(), "WECOM_EXTERNAL_CONTACT_RESPONSE_UNKNOWN");
                }
            } catch (Exception ex) {
                result = WecomSendResult.unknown(sender.mode(), "WECOM_EXTERNAL_CONTACT_RESPONSE_UNKNOWN");
            }
            finishSend(preparation.run(), owner, result);
        } finally {
            sendFenceService.finish(decision.authorization());
        }
    }

    public void recoverUnknown(long tenantId, long runId) {
        transactions.executeWithoutResult(status -> {
            AiChatDelegationRun current = repository.findRunForUpdate(tenantId, runId).orElse(null);
            if (current == null || !"SENDING".equals(current.status())) {
                return;
            }
            String reason = "WORKER_LEASE_EXPIRED_UNKNOWN";
            if (repository.updateRunStatus(tenantId, runId, current.version(), "SENDING", "UNKNOWN",
                    reason, null, clock.instant()) != 1) {
                return;
            }
            repository.insertAttempt(attempt(current, "LEASE_RECOVERY", "UNKNOWN", current.sendProvider(),
                    null, reason));
            auditService.record(tenantId, null, "AI_CHAT_DELEGATION_SEND_UNKNOWN", "AI_CHAT_DELEGATION_RUN",
                    Long.toString(runId), null, auditSnapshot("UNKNOWN", reason, current.sendProvider()), null);
        });
        sendFenceService.recover(tenantId, runId);
    }

    private GenerationPreparation prepareGeneration(long tenantId, long runId, String owner) {
        return transactions.execute(status -> {
            AiChatDelegationRun run = ownedRunForUpdate(tenantId, runId, "GENERATING", owner);
            Customer customer = customerRepository.findByIdForUpdate(tenantId, run.customerId()).orElse(null);
            String reason = complianceBlockReason(tenantId, run, customer);
            AiChatDelegationPolicy policy = policy(tenantId);
            if (reason == null) {
                reason = policyBlockReason(policy, run);
            }
            if (reason != null) {
                transitionGate(run, "GENERATING", "SKIPPED", reason, null, "COMPLIANCE");
                return null;
            }
            Instant now = clock.instant();
            if (!withinAllowedHours(now, policy)) {
                Instant next = nextOpening(now, policy);
                transitionGate(run, "GENERATING", "DEFERRED", "OUTSIDE_ALLOWED_HOURS", next, "COMPLIANCE");
                return null;
            }
            if (!providerConfigService.workerEnabled(tenantId)) {
                transitionGate(run, "GENERATING", "FAILED", "AI_PROVIDER_DISABLED", null, "GENERATE");
                return null;
            }
            return new GenerationPreparation(run, policy);
        });
    }

    private SendPreparation prepareSend(long tenantId, long runId, String owner) {
        return transactions.execute(status -> {
            AiChatDelegationRun candidate = repository.findRun(tenantId, runId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI托管流水不存在"));
            AiChatDelegationPolicy policy = repository.findPolicyForUpdate(tenantId)
                    .orElseGet(() -> AiChatDelegationPolicy.defaults(tenantId));
            Customer customer = customerRepository.findByIdForUpdate(tenantId, candidate.customerId()).orElse(null);
            AiChatDelegationRun run = ownedRunForUpdate(tenantId, runId, "SENDING", owner);
            String reason = complianceBlockReason(tenantId, run, customer);
            if (reason == null) {
                reason = policyBlockReason(policy, run);
            }
            if (reason != null) {
                transitionGate(run, "SENDING", "SKIPPED", reason, null, "COMPLIANCE");
                return null;
            }
            Instant now = clock.instant();
            if (!withinAllowedHours(now, policy)) {
                Instant next = nextOpening(now, policy);
                transitionGate(run, "SENDING", "READY_TO_SEND", "OUTSIDE_ALLOWED_HOURS", next, "COMPLIANCE");
                return null;
            }
            ZoneId zone = ZoneId.of(policy.zoneId());
            Instant dayStart = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
            if (repository.countSendAttemptsSince(tenantId, run.customerId(), dayStart) >= policy.dailyLimit()) {
                Instant next = nextOpening(now.plus(Duration.ofDays(1)), policy);
                transitionGate(run, "SENDING", "READY_TO_SEND", "DAILY_LIMIT_REACHED", next, "COMPLIANCE");
                return null;
            }
            Optional<Instant> latest = repository.findLatestSendAttempt(tenantId, run.customerId());
            if (latest.isPresent()) {
                Instant earliest = latest.get().plusSeconds(policy.minIntervalSeconds());
                if (now.isBefore(earliest)) {
                    Instant next = withinAllowedHours(earliest, policy) ? earliest : nextOpening(earliest, policy);
                    transitionGate(run, "SENDING", "READY_TO_SEND", "MIN_INTERVAL_NOT_REACHED", next, "COMPLIANCE");
                    return null;
                }
            }
            String reply = cipher.decrypt(run.replyCipher());
            Optional<String> blocked = safety.replyBlockReason(reply, policy.blockedTerms());
            if (blocked.isPresent()) {
                transitionGate(run, "SENDING", "SKIPPED", blocked.get(), null, "COMPLIANCE");
                return null;
            }
            if (repository.markSendAttempted(tenantId, runId, run.version(), owner, now) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "AI托管发送状态已变化");
            }
            return new SendPreparation(run, new ExternalContactMessageCommand(tenantId, run.corpId(),
                    run.staffUserId(), run.externalUserid(), reply.strip(), "ai-delegation:" + runId));
        });
    }

    private void finishSend(AiChatDelegationRun claimed, String owner, WecomSendResult result) {
        WecomSendResult finalResult = result;
        transactions.executeWithoutResult(status -> {
            AiChatDelegationRun current = ownedRunForUpdate(claimed.tenantId(), claimed.id(), "SENDING", owner);
            String target;
            Instant next = null;
            if (finalResult.status() == WecomSendResult.Status.RETRYABLE
                    && current.sendAttempts() < MAX_SEND_ATTEMPTS) {
                target = "RETRY_SEND";
                long delay = Math.min(60, 1L << Math.min(5, current.sendAttempts()));
                next = clock.instant().plusSeconds(delay);
            } else if (finalResult.status() == WecomSendResult.Status.RETRYABLE) {
                target = "FAILED";
            } else {
                target = finalResult.status().name();
            }
            String reason = safeReason(finalResult.errorMasked());
            if (repository.finishSend(current.tenantId(), current.id(), current.version(), owner, target, reason,
                    finalResult.provider(), finalResult.providerMessageId(), next, clock.instant()) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "AI托管发送结果状态已变化");
            }
            repository.insertAttempt(attempt(current, "SEND", finalResult.status().name(), finalResult.provider(),
                    finalResult.providerMessageId(), reason));
            auditService.record(current.tenantId(), null, "AI_CHAT_DELEGATION_SEND_" + target,
                    "AI_CHAT_DELEGATION_RUN", current.id().toString(), null,
                    auditSnapshot(target, reason, finalResult.provider()), null);
        });
    }

    private void failGeneration(long tenantId, long runId, String owner, boolean terminal, String error) {
        transactions.executeWithoutResult(status -> {
            AiChatDelegationRun current = repository.findRunForUpdate(tenantId, runId).orElse(null);
            if (current == null || !"GENERATING".equals(current.status()) || !owner.equals(current.leaseOwner())) {
                return;
            }
            boolean stop = terminal || current.generationAttempts() >= MAX_GENERATION_ATTEMPTS;
            String target = stop ? "FAILED" : "RETRY_GENERATE";
            Instant next = stop ? null : clock.instant().plusSeconds(
                    Math.min(60, 1L << Math.min(5, current.generationAttempts())));
            String reason = safeReason(error);
            if (repository.updateRunStatus(tenantId, runId, current.version(), "GENERATING", target,
                    reason, next, clock.instant()) != 1) {
                return;
            }
            repository.insertAttempt(attempt(current, "GENERATE", target, null, null, reason));
            auditService.record(tenantId, null, "AI_CHAT_DELEGATION_GENERATE_" + target,
                    "AI_CHAT_DELEGATION_RUN", Long.toString(runId), null,
                    auditSnapshot(target, reason, null), null);
        });
    }

    private void finishGenerationGate(AiChatDelegationRun run, String reason) {
        transactions.executeWithoutResult(status -> {
            AiChatDelegationRun current = repository.findRunForUpdate(run.tenantId(), run.id()).orElse(null);
            if (current == null || !"GENERATING".equals(current.status())
                    || !Objects.equals(current.leaseOwner(), run.leaseOwner())) {
                return;
            }
            transitionGate(current, "GENERATING", "SKIPPED", reason, null, "GENERATE");
        });
    }

    private void transitionGate(AiChatDelegationRun run, String expected, String target, String reason,
                                Instant next, String phase) {
        if (repository.updateRunStatus(run.tenantId(), run.id(), run.version(), expected, target,
                reason, next, clock.instant()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI托管状态已变化");
        }
        repository.insertAttempt(attempt(run, phase, target, null, null, reason));
        auditService.record(run.tenantId(), null, "AI_CHAT_DELEGATION_GATE_" + target,
                "AI_CHAT_DELEGATION_RUN", run.id().toString(), null, auditSnapshot(target, reason, null), null);
    }

    private String sourceBlockReason(long tenantId, WecomMessage message, Customer customer) {
        if (!"DIRECT".equals(message.conversationType())) {
            return "ROOM_NOT_SUPPORTED";
        }
        if (!"TEXT".equals(message.messageType()) || !"CUSTOMER".equals(message.senderType())) {
            return "SOURCE_NOT_CUSTOMER_TEXT";
        }
        if (!"STORED".equals(message.status()) || message.customerId() == null) {
            return "CUSTOMER_UNRESOLVED";
        }
        if (!"GRANTED".equals(message.consentSnapshot()) || !"GRANTED".equals(message.currentConsentStatus())
                || message.consentEvidenceRef() == null || message.consentEvidenceRef().isBlank()) {
            return "CONSENT_NOT_GRANTED";
        }
        if (!wecomRepository.archiveMemberEnabled(tenantId, message.corpId(), message.staffUserId())) {
            return "MEMBER_NOT_COVERED";
        }
        if (customer == null || customer.isDeleted()) {
            return "CUSTOMER_NOT_AVAILABLE";
        }
        if (!activeStaff(tenantId, message.staffUserId())) {
            return "STAFF_NOT_ACTIVE";
        }
        WecomContact contact = wecomRepository.findContactForUpdate(tenantId, message.corpId(), message.externalUserid(),
                message.staffUserId()).orElse(null);
        if (contact == null || !"GRANTED".equals(contact.consentStatus())
                || contact.consentEvidenceRef() == null || contact.consentEvidenceRef().isBlank()
                || !"ACTIVE".equals(contact.followStatus())
                || !Objects.equals(contact.customerId(), message.customerId())) {
            return "CONTACT_NOT_AUTHORIZED";
        }
        return null;
    }

    private String complianceBlockReason(long tenantId, AiChatDelegationRun run, Customer customer) {
        if (run.customerId() == null || customer == null || customer.isDeleted()) {
            return "CUSTOMER_NOT_AVAILABLE";
        }
        WecomMessage message = wecomRepository.findMessageById(tenantId, run.sourceMessageId()).orElse(null);
        if (message == null) {
            return "SOURCE_NOT_FOUND";
        }
        String reason = sourceBlockReason(tenantId, message, customer);
        if (reason != null) {
            return reason;
        }
        if (!Objects.equals(message.customerId(), run.customerId())
                || !Objects.equals(message.staffUserId(), run.staffUserId())
                || !Objects.equals(message.externalUserid(), run.externalUserid())
                || !Objects.equals(message.corpId(), run.corpId())) {
            return "SOURCE_SNAPSHOT_MISMATCH";
        }
        if (!Objects.equals(customer.getOwnerStaffId(), run.staffUserId())
                || !Objects.equals(customer.getTeamId(), run.teamId())) {
            return "CUSTOMER_OWNERSHIP_CHANGED";
        }
        SysUser staff = userRepository.findById(tenantId, run.staffUserId()).orElse(null);
        if (staff == null || !Objects.equals(staff.getTeamId(), run.teamId())) {
            return "STAFF_TEAM_CHANGED";
        }
        return null;
    }

    private String policyBlockReason(long tenantId, long customerId, Long teamId, long staffUserId) {
        return policyBlockReason(policy(tenantId), customerId, teamId, staffUserId);
    }

    private String policyBlockReason(AiChatDelegationPolicy policy, AiChatDelegationRun run) {
        return policyBlockReason(policy, run.customerId(), run.teamId(), run.staffUserId());
    }

    private String policyBlockReason(AiChatDelegationPolicy policy, long customerId, Long teamId, long staffUserId) {
        if (policy.emergencyStopped()) {
            return "EMERGENCY_STOP";
        }
        if (!policy.enabled()) {
            return "GLOBAL_DISABLED";
        }
        if (!policy.scopeEnabled("TEAM", teamId)) {
            return "TEAM_DISABLED";
        }
        if (!policy.scopeEnabled("STAFF", staffUserId)) {
            return "STAFF_DISABLED";
        }
        String mode = repository.findCustomerControl(policy.tenantId(), customerId)
                .map(AiChatDelegationCustomerControl::mode).orElse("ACTIVE");
        if ("HUMAN_TAKEOVER".equals(mode)) {
            return "HUMAN_TAKEOVER";
        }
        if ("PAUSED".equals(mode)) {
            return "CUSTOMER_PAUSED";
        }
        return "ACTIVE".equals(mode) ? null : "CUSTOMER_CONTROL_INVALID";
    }

    private AiChatDelegationPolicy policy(long tenantId) {
        return repository.findPolicy(tenantId).orElseGet(() -> AiChatDelegationPolicy.defaults(tenantId));
    }

    private boolean activeStaff(long tenantId, long staffUserId) {
        return userRepository.findById(tenantId, staffUserId).filter(SysUser::isActive)
                .filter(user -> "EMPLOYEE".equals(user.getUserType())).isPresent();
    }

    private AiChatDelegationRun ownedRunForUpdate(long tenantId, long runId, String status, String owner) {
        AiChatDelegationRun run = repository.findRunForUpdate(tenantId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI托管流水不存在"));
        if (!status.equals(run.status()) || !owner.equals(run.leaseOwner())) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI托管租约已变化");
        }
        return run;
    }

    private boolean withinAllowedHours(Instant instant, AiChatDelegationPolicy policy) {
        LocalTime time = instant.atZone(ZoneId.of(policy.zoneId())).toLocalTime();
        if (policy.allowedStart().isBefore(policy.allowedEnd())) {
            return !time.isBefore(policy.allowedStart()) && time.isBefore(policy.allowedEnd());
        }
        return !time.isBefore(policy.allowedStart()) || time.isBefore(policy.allowedEnd());
    }

    private Instant nextOpening(Instant instant, AiChatDelegationPolicy policy) {
        ZoneId zone = ZoneId.of(policy.zoneId());
        ZonedDateTime value = instant.atZone(zone);
        ZonedDateTime candidate = value.with(policy.allowedStart());
        if (!value.toLocalTime().isBefore(policy.allowedStart()) || !candidate.isAfter(value)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }

    private AiChatDelegationAttempt attempt(AiChatDelegationRun run, String phase, String result,
                                            String provider, String providerMessageId, String reason) {
        return new AiChatDelegationAttempt(null, run.tenantId(), run.id(), phase, result, provider,
                providerMessageId, safeReason(reason), clock.instant());
    }

    private Map<String, Object> auditSnapshot(String status, String reason, String provider) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status);
        if (reason != null) {
            snapshot.put("reason", reason);
        }
        if (provider != null) {
            snapshot.put("provider", provider);
        }
        return snapshot;
    }

    private String safeReason(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.replace('\r', ' ').replace('\n', ' ').strip();
        return clean.substring(0, Math.min(500, clean.length()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("AI托管结构序列化失败", ex);
        }
    }

    private record GenerationPreparation(AiChatDelegationRun run, AiChatDelegationPolicy policy) { }

    private record SendPreparation(AiChatDelegationRun run, ExternalContactMessageCommand command) { }
}
