package com.whale.crm.complaint.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.complaint.api.dto.ComplaintKeywordResponse;
import com.whale.crm.complaint.api.dto.ComplaintPolicyResponse;
import com.whale.crm.complaint.api.dto.CreateComplaintKeywordRequest;
import com.whale.crm.complaint.api.dto.UpdateComplaintKeywordRequest;
import com.whale.crm.complaint.api.dto.UpdateComplaintPolicyRequest;
import com.whale.crm.complaint.domain.ComplaintKeyword;
import com.whale.crm.complaint.domain.ComplaintPolicy;
import com.whale.crm.complaint.domain.ComplaintRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ComplaintSettingsService {
    private static final String KEYWORD_CREATE_SCOPE = "complaint.keyword.create";
    private static final String KEYWORD_UPDATE_SCOPE = "complaint.keyword.update";
    private static final String KEYWORD_DISABLE_SCOPE = "complaint.keyword.disable";
    private static final String POLICY_UPDATE_SCOPE = "complaint.policy.update";

    private final ComplaintRepository repository;
    private final ComplaintAccessService accessService;
    private final ComplaintMasker masker;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ComplaintSettingsService(ComplaintRepository repository, ComplaintAccessService accessService,
                                    ComplaintMasker masker, UserRepository userRepository,
                                    RoleRepository roleRepository, IdempotencyService idempotencyService,
                                    AuditService auditService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.accessService = accessService;
        this.masker = masker;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ComplaintPolicyResponse getPolicy(CurrentUser user) {
        accessService.require(user, "complaint:config");
        return ComplaintPolicyResponse.from(policy(user.tenantId()));
    }

    @Transactional(readOnly = true)
    public List<ComplaintKeywordResponse> listKeywords(CurrentUser user) {
        accessService.require(user, "complaint:config");
        return repository.findKeywords(user.tenantId()).stream().map(ComplaintKeywordResponse::from).toList();
    }

    @Transactional
    public ComplaintKeywordResponse createKeyword(CurrentUser user, CreateComplaintKeywordRequest request,
                                                   String idempotencyKey) {
        accessService.require(user, "complaint:config");
        String keyword = masker.required(request.keyword(), "关键词", 128);
        String payload = payload(keyword);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), KEYWORD_CREATE_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            return replayKeyword(user.tenantId(), resourceId(replay.orElseThrow()));
        }
        if (repository.findKeywordByText(user.tenantId(), keyword).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "关键词已存在，可修改或停用原配置");
        }
        repository.insertKeyword(new ComplaintKeyword(null, user.tenantId(), keyword, "ACTIVE", user.userId(),
                null, null, 0L));
        ComplaintKeyword stored = repository.findKeywordByText(user.tenantId(), keyword).orElseThrow();
        auditService.record(user.tenantId(), user.userId(), "COMPLAINT_KEYWORD_CREATE", "COMPLAINT_KEYWORD",
                stored.id().toString(), null, keywordSnapshot(stored), null);
        idempotencyService.recordSuccess(user.tenantId(), KEYWORD_CREATE_SCOPE, idempotencyKey,
                "COMPLAINT_KEYWORD", stored.id().toString(), payload);
        return ComplaintKeywordResponse.from(stored);
    }

    @Transactional
    public ComplaintKeywordResponse updateKeyword(CurrentUser user, long id, UpdateComplaintKeywordRequest request,
                                                   String idempotencyKey) {
        accessService.require(user, "complaint:config");
        String keyword = masker.required(request.keyword(), "关键词", 128);
        String reason = masker.requiredMasked(request.reason(), "修改理由", 1000);
        String payload = payload(id, request.version(), keyword, reason);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), KEYWORD_UPDATE_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return replayKeyword(user.tenantId(), id);
        }
        ComplaintKeyword before = repository.findKeywordByIdForUpdate(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("关键词不存在"));
        requireVersion(before.version(), request.version(), "关键词已变化，请刷新后重试");
        repository.findKeywordByText(user.tenantId(), keyword)
                .filter(other -> !other.id().equals(id))
                .ifPresent(other -> {
                    throw new BusinessException(ErrorCode.CONFLICT, "关键词已存在");
                });
        if (repository.updateKeyword(user.tenantId(), id, request.version(), keyword) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "关键词已变化，请刷新后重试");
        }
        ComplaintKeyword after = keyword(user.tenantId(), id);
        auditService.record(user.tenantId(), user.userId(), "COMPLAINT_KEYWORD_UPDATE", "COMPLAINT_KEYWORD",
                Long.toString(id), keywordSnapshot(before), keywordSnapshot(after), reason);
        idempotencyService.recordSuccess(user.tenantId(), KEYWORD_UPDATE_SCOPE, idempotencyKey,
                "COMPLAINT_KEYWORD", Long.toString(id), payload);
        return ComplaintKeywordResponse.from(after);
    }

    @Transactional
    public ComplaintKeywordResponse disableKeyword(CurrentUser user, long id, long version, String reasonInput,
                                                    String idempotencyKey) {
        accessService.require(user, "complaint:config");
        String reason = masker.requiredMasked(reasonInput, "停用理由", 1000);
        String payload = payload(id, version, reason);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), KEYWORD_DISABLE_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return replayKeyword(user.tenantId(), id);
        }
        ComplaintKeyword before = repository.findKeywordByIdForUpdate(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("关键词不存在"));
        requireVersion(before.version(), version, "关键词已变化，请刷新后重试");
        if (!"ACTIVE".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "关键词已停用");
        }
        if (repository.disableKeyword(user.tenantId(), id, version) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "关键词已变化，请刷新后重试");
        }
        ComplaintKeyword after = keyword(user.tenantId(), id);
        auditService.record(user.tenantId(), user.userId(), "COMPLAINT_KEYWORD_DISABLE", "COMPLAINT_KEYWORD",
                Long.toString(id), keywordSnapshot(before), keywordSnapshot(after), reason);
        idempotencyService.recordSuccess(user.tenantId(), KEYWORD_DISABLE_SCOPE, idempotencyKey,
                "COMPLAINT_KEYWORD", Long.toString(id), payload);
        return ComplaintKeywordResponse.from(after);
    }

    @Transactional
    public ComplaintPolicyResponse updatePolicy(CurrentUser user, UpdateComplaintPolicyRequest request,
                                                 String idempotencyKey) {
        accessService.require(user, "complaint:config");
        String script = masker.required(request.empathyScript(), "共情话术", 2000);
        String reason = masker.requiredMasked(request.reason(), "修改理由", 1000);
        String payload = payload(request.enabled(), request.fallbackRecipientUserId(), script,
                request.version(), reason);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), POLICY_UPDATE_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            return ComplaintPolicyResponse.from(policy(user.tenantId()));
        }
        validateFallback(user.tenantId(), request.fallbackRecipientUserId());
        ComplaintPolicy before = repository.findPolicyForUpdate(user.tenantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "客诉策略尚未初始化"));
        requireVersion(before.version(), request.version(), "客诉策略已变化，请刷新后重试");
        if (repository.updatePolicy(user.tenantId(), request.version(), request.enabled(),
                request.fallbackRecipientUserId(), script) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "客诉策略已变化，请刷新后重试");
        }
        ComplaintPolicy after = policy(user.tenantId());
        auditService.record(user.tenantId(), user.userId(), "COMPLAINT_POLICY_UPDATE", "COMPLAINT_POLICY",
                after.id().toString(), policySnapshot(before), policySnapshot(after), reason);
        idempotencyService.recordSuccess(user.tenantId(), POLICY_UPDATE_SCOPE, idempotencyKey,
                "COMPLAINT_POLICY", after.id().toString(), payload);
        return ComplaintPolicyResponse.from(after);
    }

    private void validateFallback(long tenantId, Long recipientId) {
        if (recipientId == null) {
            return;
        }
        userRepository.findById(tenantId, recipientId)
                .filter(SysUser::isActive)
                .filter(user -> "EMPLOYEE".equals(user.getUserType()))
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "默认接收人不是有效员工"));
        if (!roleRepository.findActivePermissionCodes(tenantId, recipientId).contains("complaint:read")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "默认接收人缺少客诉查看权限");
        }
    }

    private ComplaintPolicy policy(long tenantId) {
        return repository.findPolicy(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "客诉策略尚未初始化"));
    }

    private ComplaintKeyword keyword(long tenantId, long id) {
        return repository.findKeywordById(tenantId, id)
                .orElseThrow(() -> accessService.notFound("关键词不存在"));
    }

    private ComplaintKeywordResponse replayKeyword(long tenantId, long id) {
        return ComplaintKeywordResponse.from(keyword(tenantId, id));
    }

    private void requireVersion(long actual, long expected, String message) {
        if (actual != expected) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }

    private long resourceId(IdempotencyRecord record) {
        try {
            return Long.parseLong(record.getResourceId());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果资源无效");
        }
    }

    private void requireResource(IdempotencyRecord record, long id) {
        if (!Long.toString(id).equals(record.getResourceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果与请求资源不一致");
        }
    }

    private Map<String, Object> keywordSnapshot(ComplaintKeyword keyword) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("keyword", keyword.keyword());
        snapshot.put("status", keyword.status());
        snapshot.put("version", keyword.version());
        return snapshot;
    }

    private Map<String, Object> policySnapshot(ComplaintPolicy policy) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("enabled", policy.enabled());
        snapshot.put("fallbackRecipientUserId", policy.fallbackRecipientUserId());
        snapshot.put("empathyScript", masker.mask(policy.empathyScript(), 2000));
        snapshot.put("version", policy.version());
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
