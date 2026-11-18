package com.whale.crm.quickreply.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.application.AiReplySuggestionService;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.quickreply.api.dto.QuickReplyUsageRequest;
import com.whale.crm.quickreply.api.dto.QuickReplyUsageResponse;
import com.whale.crm.quickreply.domain.QuickReplyRepository;
import com.whale.crm.quickreply.domain.QuickReplyTemplate;
import com.whale.crm.quickreply.domain.QuickReplyUsage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class QuickReplyUsageService {
    private static final String RECORD_SCOPE = "quickreply.usage.record";

    private final QuickReplyRepository repository;
    private final WecomRepository wecomRepository;
    private final CustomerScopeSupport scopeSupport;
    private final QuickReplyTextMasker masker;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AiReplySuggestionService aiReplyService;

    public QuickReplyUsageService(QuickReplyRepository repository, WecomRepository wecomRepository,
                                  CustomerScopeSupport scopeSupport, QuickReplyTextMasker masker,
                                  IdempotencyService idempotencyService, AuditService auditService,
                                  ObjectMapper objectMapper, AiReplySuggestionService aiReplyService) {
        this.repository = repository;
        this.wecomRepository = wecomRepository;
        this.scopeSupport = scopeSupport;
        this.masker = masker;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.aiReplyService = aiReplyService;
    }

    @Transactional
    public QuickReplyUsageResponse record(CurrentUser user, long templateId, QuickReplyUsageRequest request,
                                          String idempotencyKey) {
        requireView(user);
        String payload = payload(templateId, request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(user.tenantId(), RECORD_SCOPE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            return QuickReplyUsageResponse.from(usage(user.tenantId(), idempotencyKey));
        }
        QuickReplyTemplate template = repository.findTemplateById(user.tenantId(), templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "话术不存在或不可见"));
        if (!"ACTIVE".equals(template.status()) || !visible(user, template.scopeType(), template.scopeId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "话术不存在或不可见");
        }
        if (!template.scene().equals(request.scene())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "话术与业务场景不匹配");
        }
        scopeSupport.requireVisible(user.tenantId(), request.customerId(), user);
        boolean mapped = wecomRepository.contactMapped(user.tenantId(), request.externalUserid(), request.customerId());
        if (!mapped) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "企微客户与 CRM 客户映射不一致");
        }
        String original = masker.required(request.originalContent(), "原始话术", 2000);
        String finalContent = masker.required(request.finalContent(), "最终话术", 4000);
        if (request.aiSuggestionId() != null) {
            AiReplySuggestionService.AcceptedAiReply accepted = aiReplyService.acceptedForUsage(user,
                    request.aiSuggestionId(), request.customerId());
            String acceptedOriginal = masker.required(accepted.originalContent(), "AI 原始回复", 2000);
            String acceptedFinal = masker.required(accepted.finalContent(), "AI 采纳回复", 4000);
            if (accepted.templateId() != templateId || !accepted.scene().equals(request.scene())
                    || !acceptedOriginal.equals(original) || !acceptedFinal.equals(finalContent)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "AI 使用日志必须与已采纳候选的模板、场景和最终文本一致");
            }
        }
        String clientResult = request.clientResult() == null || request.clientResult().isBlank() ? null
                : masker.mask(request.clientResult(), 512);
        QuickReplyUsage usage = new QuickReplyUsage(null, user.tenantId(), templateId, user.userId(),
                request.customerId(), request.externalUserid().strip(), request.scene(), original, finalContent,
                request.edited() || !original.equals(finalContent), request.sendStatus(), clientResult,
                request.aiSuggestionId(), Instant.now(), idempotencyKey, null);
        repository.insertUsage(usage);
        QuickReplyUsage stored = usage(user.tenantId(), idempotencyKey);
        auditService.record(user.tenantId(), user.userId(), "QUICK_REPLY_USAGE_RECORD", "QUICK_REPLY_USAGE",
                stored.id().toString(), null, snapshot(stored), "员工复制或企微当前会话 SDK 回调");
        idempotencyService.recordSuccess(user.tenantId(), RECORD_SCOPE, idempotencyKey, "QUICK_REPLY_USAGE",
                stored.id().toString(), payload);
        return QuickReplyUsageResponse.from(stored);
    }

    @Transactional(readOnly = true)
    public PageResponse<QuickReplyUsageResponse> search(CurrentUser user, Long templateId, Long employeeId,
                                                         int page, int size) {
        requireView(user);
        Long effectiveEmployee = user.userId();
        if (user.permissions().contains("quickreply:manage")) {
            effectiveEmployee = employeeId;
        } else if (employeeId != null && employeeId != user.userId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能查看其他员工的话术使用记录");
        }
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.findUsages(user.tenantId(), templateId, effectiveEmployee, offset, size)
                .stream().map(QuickReplyUsageResponse::from).toList(),
                repository.countUsages(user.tenantId(), templateId, effectiveEmployee), page, size);
    }

    private QuickReplyUsage usage(long tenantId, String key) {
        return repository.findUsageByKey(tenantId, key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "话术使用记录不存在"));
    }

    private void requireView(CurrentUser user) {
        if (!user.permissions().contains("quickreply:view")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有快捷话术使用权限");
        }
    }

    private boolean visible(CurrentUser user, String scopeType, Long scopeId) {
        return switch (scopeType) {
            case "GLOBAL" -> true;
            case "PERSONAL" -> scopeId != null && scopeId == user.userId();
            case "DEPARTMENT" -> scopeId != null && scopeId.equals(user.teamId());
            case "INSTITUTION" -> scopeId != null && user.institutionIds().contains(scopeId);
            default -> false;
        };
    }

    private Map<String, Object> snapshot(QuickReplyUsage usage) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("templateId", usage.templateId());
        snapshot.put("customerId", usage.customerId());
        snapshot.put("scene", usage.scene());
        snapshot.put("edited", usage.edited());
        snapshot.put("sendStatus", usage.sendStatus());
        snapshot.put("aiSuggestionId", usage.aiSuggestionId());
        snapshot.put("originalContent", usage.originalContent());
        snapshot.put("finalContent", usage.finalContent());
        return snapshot;
    }

    private String payload(Object... values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等请求序列化失败", ex);
        }
    }
}
