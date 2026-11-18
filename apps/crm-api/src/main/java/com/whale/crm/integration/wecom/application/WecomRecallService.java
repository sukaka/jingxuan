package com.whale.crm.integration.wecom.application;

import com.whale.crm.integration.wecom.adapter.WecomArchiveMessage;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRecallEvent;
import com.whale.crm.integration.wecom.domain.WecomRecallRepository;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.application.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Service
public class WecomRecallService {
    private final WecomRecallRepository recallRepository;
    private final WecomRepository messageRepository;
    private final OutboxService outboxService;
    private final AuditService auditService;

    public WecomRecallService(WecomRecallRepository recallRepository, WecomRepository messageRepository,
                              OutboxService outboxService, AuditService auditService) {
        this.recallRepository = recallRepository;
        this.messageRepository = messageRepository;
        this.outboxService = outboxService;
        this.auditService = auditService;
    }

    @Transactional
    public RecallResult process(long tenantId, String corpId, WecomArchiveMessage source) {
        if (source.previousMessageId() == null || source.previousMessageId().isBlank()
                || source.previousMessageId().equals(source.msgId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "撤回消息缺少有效的原消息 ID");
        }
        var existing = recallRepository.findByRecallMessageId(tenantId, corpId, source.msgId());
        if (existing.isPresent()) {
            if ("PENDING_ORIGINAL".equals(existing.get().status())) {
                messageRepository.findMessage(tenantId, corpId, existing.get().originalMessageId())
                        .ifPresent(original -> apply(existing.get(), original));
            }
            return RecallResult.REPLAY;
        }
        WecomRecallEvent input = new WecomRecallEvent(null, tenantId, corpId, source.msgId(),
                source.previousMessageId(), source.seq(), source.externalUserid(), source.staffUserId(),
                source.senderType(), source.sentAt(), "PENDING_ORIGINAL", null, null, null, 0,
                source.conversationType(), source.conversationId(), source.senderWecomUserid());
        recallRepository.insert(input);
        WecomRecallEvent event = recallRepository.findByRecallMessageId(tenantId, corpId, source.msgId()).orElseThrow();
        return messageRepository.findMessage(tenantId, corpId, source.previousMessageId())
                .map(original -> {
                    apply(event, original);
                    return RecallResult.APPLIED;
                }).orElse(RecallResult.PENDING);
    }

    @Transactional
    public Long applyPending(long tenantId, String corpId, WecomMessage original) {
        return recallRepository.findPendingByOriginalMessageId(tenantId, corpId, original.msgId())
                .map(event -> {
                    apply(event, original);
                    return event.recallSeq();
                }).orElse(null);
    }

    private void apply(WecomRecallEvent event, WecomMessage original) {
        if (!Objects.equals(original.staffUserId(), event.staffUserId())
                || !Objects.equals(original.externalUserid(), event.externalUserid())
                || !Objects.equals(original.senderType(), event.senderType())
                || !Objects.equals(original.conversationType(), event.conversationType())
                || (original.room() && (!Objects.equals(original.conversationId(), event.conversationId())
                || original.senderWecomUserid() == null
                || !Objects.equals(original.senderWecomUserid(), event.senderWecomUserid())))) {
            throw new BusinessException(ErrorCode.CONFLICT, "撤回事件与原消息的会话、员工、客户或实际发送方不一致");
        }
        Instant recalledAt = event.occurredAt();
        if (original.recalledAt() == null) {
            if (messageRepository.markMessageRecalled(event.tenantId(), original.corpId(), original.msgId(),
                    original.version(), recalledAt, event.recallMessageId()) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "原消息已被撤回或版本已变化");
            }
        } else if (!event.recallMessageId().equals(original.recallMessageId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "原消息已由其他撤回事件处理");
        } else {
            recalledAt = original.recalledAt();
        }
        if (recallRepository.markApplied(event.tenantId(), event.id(), event.version(), original.id(), recalledAt) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "撤回事件已被补全");
        }
        Map<String, Object> after = Map.of("messageRef", original.id(), "recalledAt", recalledAt.toString(),
                "recallMessageId", event.recallMessageId());
        auditService.record(event.tenantId(), null, "WECOM_MESSAGE_RECALLED", "WECOM_MESSAGE",
                original.id().toString(), Map.of("recalled", false), after, "企微官方撤回控制事件");
        outboxService.append(event.tenantId(), "wecom.message.recalled.v1:" + event.corpId() + ":" + event.recallMessageId(),
                "wecom.message.recalled.v1", "wecom", original.id().toString(), after);
    }

    public enum RecallResult {
        APPLIED,
        PENDING,
        REPLAY
    }
}
