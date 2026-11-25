package com.whale.crm.complaint.application;

import com.whale.crm.complaint.domain.ComplaintHit;
import com.whale.crm.complaint.domain.ComplaintHitHistory;
import com.whale.crm.complaint.domain.ComplaintKeyword;
import com.whale.crm.complaint.domain.ComplaintPolicy;
import com.whale.crm.complaint.domain.ComplaintRepository;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.Followup;
import com.whale.crm.customer.domain.FollowupRepository;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationRequest;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ComplaintDetectionService {
    public static final String SOURCE_FOLLOWUP = "FOLLOWUP";
    public static final String SOURCE_WECOM = "WECOM_MESSAGE";

    private final ComplaintRepository repository;
    private final CustomerRepository customerRepository;
    private final FollowupRepository followupRepository;
    private final WecomRepository wecomRepository;
    private final WecomArchiveService wecomArchiveService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final NotificationRequestService notificationRequestService;
    private final AuditService auditService;
    private final ComplaintMasker masker;

    public ComplaintDetectionService(ComplaintRepository repository, CustomerRepository customerRepository,
                                     FollowupRepository followupRepository, WecomRepository wecomRepository,
                                     WecomArchiveService wecomArchiveService, UserRepository userRepository,
                                     RoleRepository roleRepository, NotificationRequestService notificationRequestService,
                                     AuditService auditService, ComplaintMasker masker) {
        this.repository = repository;
        this.customerRepository = customerRepository;
        this.followupRepository = followupRepository;
        this.wecomRepository = wecomRepository;
        this.wecomArchiveService = wecomArchiveService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.notificationRequestService = notificationRequestService;
        this.auditService = auditService;
        this.masker = masker;
    }

    @Transactional
    public List<ComplaintHit> detectFollowup(long tenantId, long followupId, Long expectedVersion) {
        Followup followup = followupRepository.findById(tenantId, followupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在"));
        requireSourceVersion(followup.getVersion(), expectedVersion);
        if (followup.isDeleted() || followup.getSummary() == null || followup.getSummary().isBlank()) {
            return List.of();
        }
        Customer customer = customer(tenantId, followup.getCustomerId());
        if (customer == null || !activeEmployee(tenantId, followup.getOwnerStaffId())) {
            return List.of();
        }
        return detect(tenantId, customer, SOURCE_FOLLOWUP, Long.toString(followupId), followup.getSummary());
    }

    @Transactional
    public List<ComplaintHit> detectWecom(long tenantId, long messageId, Long expectedVersion) {
        WecomMessage message = wecomRepository.findMessageById(tenantId, messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "企微消息不存在"));
        requireSourceVersion(message.version(), expectedVersion);
        if (!eligible(tenantId, message)) {
            return List.of();
        }
        WecomContact contact = wecomRepository.findContact(tenantId, message.corpId(), message.externalUserid(),
                message.staffUserId()).orElse(null);
        if (contact == null || !"GRANTED".equals(contact.consentStatus())
                || !Objects.equals(contact.customerId(), message.customerId())
                || !activeEmployee(tenantId, message.staffUserId())) {
            return List.of();
        }
        Customer customer = customer(tenantId, message.customerId());
        if (customer == null) {
            return List.of();
        }
        String text = wecomArchiveService.readContent(message);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return detect(tenantId, customer, SOURCE_WECOM, Long.toString(messageId), text);
    }

    private boolean eligible(long tenantId, WecomMessage message) {
        return "DIRECT".equals(message.conversationType()) && message.staffUserId() != null
                && "TEXT".equals(message.messageType()) && "STORED".equals(message.status())
                && message.customerId() != null && "GRANTED".equals(message.consentSnapshot())
                && "GRANTED".equals(message.currentConsentStatus())
                && wecomRepository.archiveMemberEnabled(tenantId, message.corpId(), message.staffUserId());
    }

    private List<ComplaintHit> detect(long tenantId, Customer customer, String sourceType, String sourceId,
                                      String sourceText) {
        ComplaintPolicy policy = repository.findPolicy(tenantId).orElse(null);
        if (policy == null || !policy.enabled()) {
            return List.of();
        }
        List<ComplaintKeyword> keywords = repository.findActiveKeywords(tenantId);
        if (keywords.isEmpty()) {
            return List.of();
        }
        String normalizedText = sourceText.toLowerCase(Locale.ROOT);
        RecipientResolution recipient = resolveRecipient(tenantId, customer.getTeamId(), policy);
        for (ComplaintKeyword keyword : keywords) {
            if (!normalizedText.contains(keyword.keyword().toLowerCase(Locale.ROOT))) {
                continue;
            }
            ComplaintHit candidate = new ComplaintHit(null, tenantId, customer.getId(), customer.getOwnerStaffId(),
                    customer.getTeamId(), sourceType, sourceId, keyword.id(), keyword.keyword(),
                    masker.mask(sourceText, 2000), "OPEN", recipient.recipientId() == null ? "EXCEPTION" : "ROUTED",
                    recipient.error(), recipient.recipientId(), recipient.source(), policy.version(),
                    null, null, null, null, null, null, 0L);
            if (repository.insertHit(candidate) == 0) {
                continue;
            }
            ComplaintHit hit = repository.findHitBySource(tenantId, sourceType, sourceId, keyword.id()).orElseThrow();
            repository.insertHitHistory(new ComplaintHitHistory(null, tenantId, hit.id(), "DETECT", null,
                    "OPEN", null, "关键词规则命中，待人工核查", null));
            auditService.record(tenantId, null, "COMPLAINT_HIT_DETECT", "COMPLAINT_HIT", hit.id().toString(),
                    null, hitSnapshot(hit), null);
            if (hit.recipientUserIdSnapshot() != null) {
                notifyRecipient(hit);
            }
        }
        return repository.findHitsBySource(tenantId, sourceType, sourceId);
    }

    private RecipientResolution resolveRecipient(long tenantId, Long teamId, ComplaintPolicy policy) {
        if (teamId != null) {
            Long leaderId = repository.findActiveTeamLeaderId(tenantId, teamId);
            if (leaderId != null && activeEmployee(tenantId, leaderId)) {
                return new RecipientResolution(leaderId, "TEAM_LEADER", null);
            }
            var managers = userRepository.findStewardOptions(tenantId, List.of("MANAGER_LEAD"), teamId, null);
            if (managers.size() == 1) {
                return new RecipientResolution(managers.getFirst().id(), "TEAM_MANAGER_LEAD", null);
            }
            if (managers.size() > 1) {
                return new RecipientResolution(null, "NONE", "AMBIGUOUS_TEAM_MANAGER_LEAD");
            }
        }
        Long fallbackId = policy.fallbackRecipientUserId();
        if (fallbackId != null && activeEmployee(tenantId, fallbackId)
                && roleRepository.findActivePermissionCodes(tenantId, fallbackId).contains("complaint:read")) {
            return new RecipientResolution(fallbackId, "FALLBACK", null);
        }
        return new RecipientResolution(null, "NONE", "NO_RECIPIENT");
    }

    private void notifyRecipient(ComplaintHit hit) {
        String sourceLabel = switch (hit.sourceType()) {
            case "WECOM_MESSAGE" -> "企微聊天消息";
            case "FOLLOWUP" -> "跟进记录";
            default -> "业务记录";
        };
        String content = "客户 #" + hit.customerId() + " 的" + sourceLabel + " #" + hit.sourceId()
                + " 命中关键词「" + hit.keywordSnapshot() + "」，仅待人工核查，不构成正式客诉。证据："
                + hit.evidenceMasked();
        notificationRequestService.request(new NotificationRequest(hit.tenantId(), hit.recipientUserIdSnapshot(),
                "COMPLAINT_KEYWORD_HIT", "客诉关键词命中待核查", masker.mask(content, 4000), "COMPLAINT_HIT",
                hit.id().toString(), "complaint:hit:" + hit.id(), "/complaints?hitId=" + hit.id(),
                Set.of(NotificationChannel.IN_APP, NotificationChannel.WECOM_APP)));
    }

    private Customer customer(long tenantId, Long customerId) {
        if (customerId == null) {
            return null;
        }
        return customerRepository.findById(tenantId, customerId)
                .filter(value -> !value.isDeleted()).orElse(null);
    }

    private boolean activeEmployee(long tenantId, Long userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(tenantId, userId)
                .filter(SysUser::isActive)
                .filter(user -> "EMPLOYEE".equals(user.getUserType()))
                .isPresent();
    }

    private void requireSourceVersion(Long actual, Long expected) {
        if (expected != null && (actual == null || actual.longValue() != expected.longValue())) {
            throw new BusinessException(ErrorCode.CONFLICT, "来源记录已变化，请刷新后重试");
        }
    }

    private Map<String, Object> hitSnapshot(ComplaintHit hit) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("customerId", hit.customerId());
        snapshot.put("sourceType", hit.sourceType());
        snapshot.put("sourceId", hit.sourceId());
        snapshot.put("keywordId", hit.keywordId());
        snapshot.put("routingStatus", hit.routingStatus());
        snapshot.put("routingError", hit.routingError());
        snapshot.put("recipientUserIdSnapshot", hit.recipientUserIdSnapshot());
        snapshot.put("recipientSourceSnapshot", hit.recipientSourceSnapshot());
        snapshot.put("policyVersionSnapshot", hit.policyVersionSnapshot());
        return snapshot;
    }

    private record RecipientResolution(Long recipientId, String source, String error) {
    }
}
