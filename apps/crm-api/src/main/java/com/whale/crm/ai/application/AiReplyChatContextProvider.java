package com.whale.crm.ai.application;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 读取客户最近已授权的企微文本存档消息作为 AI 回复只读上下文。
 * 严格遵守：需具备 wecom:read 权限、客户联系人当前归档同意为 GRANTED 且启用归档成员覆盖、
 * 在当前用户 DataScope 与 tenantId 范围内、排除已撤回与非文本消息；不满足时安全回退为空列表。
 */
@Component
public class AiReplyChatContextProvider {
    static final int MAX_CONTEXT_MESSAGES = 10;
    static final int FETCH_LIMIT = 50;
    static final int MAX_MESSAGE_TEXT_LENGTH = 300;
    static final int MAX_TOTAL_TEXT_LENGTH = 3000;
    private static final int MAX_RAW_MASK_INPUT = 4000;
    private static final Set<String> ALLOWED_SENDERS = Set.of("CUSTOMER", "EMPLOYEE");
    private static final Logger LOG = LoggerFactory.getLogger(AiReplyChatContextProvider.class);

    private final WecomRepository wecomRepository;
    private final WecomArchiveService archiveService;
    private final CustomerScopeSupport scopeSupport;
    private final QuickReplyTextMasker masker;

    public AiReplyChatContextProvider(WecomRepository wecomRepository, WecomArchiveService archiveService,
                                      CustomerScopeSupport scopeSupport, QuickReplyTextMasker masker) {
        this.wecomRepository = wecomRepository;
        this.archiveService = archiveService;
        this.scopeSupport = scopeSupport;
        this.masker = masker;
    }

    public List<AiWorkerClient.MessageInput> loadRecentConsentedMessages(CurrentUser user, long customerId) {
        if (user == null || !user.permissions().contains("wecom:read")) {
            return List.of();
        }
        DataScope scope = scopeSupport.scope(user);
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        List<WecomMessage> candidates = wecomRepository.listMessages(user.tenantId(), customerId, null, null,
                query.scopeType(), query.teamIds(), query.selfId(), 0, FETCH_LIMIT);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<ContactKey, Boolean> consentCache = new HashMap<>();
        List<AiWorkerClient.MessageInput> collected = new ArrayList<>();
        int totalChars = 0;
        for (WecomMessage message : candidates) {
            if (collected.size() >= MAX_CONTEXT_MESSAGES || totalChars >= MAX_TOTAL_TEXT_LENGTH) {
                break;
            }
            if (!isEligibleMessage(message, customerId)) {
                continue;
            }
            if (!isContactConsented(user.tenantId(), customerId, message, consentCache)) {
                continue;
            }
            String rawText;
            try {
                rawText = archiveService.readContent(message);
            } catch (BusinessException ex) {
                if (ex.errorCode() == ErrorCode.PENDING_BUSINESS_RULE) {
                    LOG.debug("Skipping WeCom chat context for AI reply because private object storage is unavailable");
                    break;
                }
                LOG.debug("Skipping unreadable archived message id={} for AI reply context: {}",
                        message.id(), ex.errorCode());
                continue;
            } catch (RuntimeException ex) {
                LOG.debug("Skipping archived message id={} due to unexpected read error", message.id());
                continue;
            }
            String safeText = sanitizeAndMask(rawText);
            if (safeText == null || safeText.isBlank()) {
                continue;
            }
            int remainingBudget = MAX_TOTAL_TEXT_LENGTH - totalChars;
            if (safeText.length() > remainingBudget) {
                safeText = truncate(safeText, remainingBudget);
                if (safeText.isBlank()) {
                    break;
                }
            }
            totalChars += safeText.length();
            collected.add(new AiWorkerClient.MessageInput(Long.toString(message.id()), message.senderType(),
                    message.sentAt(), safeText));
        }
        collected.sort(Comparator.comparing(AiWorkerClient.MessageInput::sentAt)
                .thenComparing(AiWorkerClient.MessageInput::id));
        return List.copyOf(collected);
    }

    private boolean isEligibleMessage(WecomMessage message, long customerId) {
        if (message == null || message.id() == null || message.sentAt() == null) {
            return false;
        }
        return "DIRECT".equals(message.conversationType())
                && Objects.equals(message.customerId(), customerId)
                && "STORED".equals(message.status())
                && "TEXT".equals(message.messageType())
                && message.recalledAt() == null
                && ALLOWED_SENDERS.contains(message.senderType())
                && "GRANTED".equals(message.consentSnapshot())
                && "GRANTED".equals(message.currentConsentStatus())
                && message.corpId() != null && !message.corpId().isBlank()
                && message.externalUserid() != null && !message.externalUserid().isBlank()
                && message.staffUserId() != null;
    }

    private boolean isContactConsented(long tenantId, long customerId, WecomMessage message,
                                       Map<ContactKey, Boolean> consentCache) {
        ContactKey key = new ContactKey(message.corpId(), message.externalUserid(), message.staffUserId());
        return consentCache.computeIfAbsent(key, ignored -> {
            WecomContact contact = wecomRepository.findContact(tenantId, message.corpId(),
                    message.externalUserid(), message.staffUserId()).orElse(null);
            if (contact == null
                    || !"GRANTED".equals(contact.consentStatus())
                    || !"ACTIVE".equals(contact.followStatus())
                    || !Objects.equals(contact.customerId(), customerId)) {
                return false;
            }
            return wecomRepository.archiveMemberEnabled(tenantId, message.corpId(), message.staffUserId());
        });
    }

    private String sanitizeAndMask(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(Math.min(rawText.length(), MAX_RAW_MASK_INPUT));
        boolean lastWasSpace = false;
        for (int i = 0; i < rawText.length() && cleaned.length() < MAX_RAW_MASK_INPUT; i++) {
            char ch = rawText.charAt(i);
            if (ch == '\r' || ch == '\n' || ch == '\t' || Character.isWhitespace(ch)) {
                if (!lastWasSpace && !cleaned.isEmpty()) {
                    cleaned.append(' ');
                    lastWasSpace = true;
                }
            } else if (!Character.isISOControl(ch)) {
                cleaned.append(ch);
                lastWasSpace = false;
            }
        }
        String normalized = cleaned.toString().strip();
        if (normalized.isEmpty()) {
            return null;
        }
        String masked = masker.mask(normalized, normalized.length());
        return truncate(masked, MAX_MESSAGE_TEXT_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3).stripTrailing() + "...";
    }

    private record ContactKey(String corpId, String externalUserid, long staffUserId) {
    }
}
