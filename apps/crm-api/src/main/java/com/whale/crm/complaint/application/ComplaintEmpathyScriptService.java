package com.whale.crm.complaint.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.complaint.api.dto.ComplaintEmpathyScriptResponse;
import com.whale.crm.complaint.api.dto.CreateComplaintEmpathyScriptRequest;
import com.whale.crm.complaint.api.dto.UpdateComplaintEmpathyScriptRequest;
import com.whale.crm.complaint.domain.ComplaintEmpathyScript;
import com.whale.crm.complaint.domain.ComplaintEmpathyScriptRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ComplaintEmpathyScriptService {
    private static final String CREATE_SCOPE = "complaint.empathy-script.create";
    private static final String UPDATE_SCOPE = "complaint.empathy-script.update";
    private static final String DISABLE_SCOPE = "complaint.empathy-script.disable";
    private static final String RESOURCE_TYPE = "COMPLAINT_EMPATHY_SCRIPT";

    private final ComplaintEmpathyScriptRepository repository;
    private final ComplaintAccessService accessService;
    private final ComplaintMasker masker;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ComplaintEmpathyScriptService(ComplaintEmpathyScriptRepository repository,
                                         ComplaintAccessService accessService, ComplaintMasker masker,
                                         IdempotencyService idempotencyService, AuditService auditService,
                                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.accessService = accessService;
        this.masker = masker;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ComplaintEmpathyScriptResponse> list(CurrentUser user) {
        accessService.require(user, "complaint:config");
        return repository.findScripts(user.tenantId()).stream().map(ComplaintEmpathyScriptResponse::from).toList();
    }

    @Transactional
    public ComplaintEmpathyScriptResponse create(CurrentUser user, CreateComplaintEmpathyScriptRequest request,
                                                  String idempotencyKey) {
        accessService.require(user, "complaint:config");
        String title = masker.required(request.title(), "话术标题", 64);
        String script = masker.requiredMultiline(request.script(), "话术内容", 2000);
        String reason = masker.requiredMasked(request.reason(), "新增理由", 1000);
        String payload = payload(title, script, reason);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), CREATE_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            return replayScript(user, resourceId(replay.orElseThrow()));
        }
        if (repository.findScriptByTitle(user.tenantId(), title).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "话术标题已存在，可修改或停用原话术");
        }
        repository.insertScript(new ComplaintEmpathyScript(null, user.tenantId(), title, script, "ACTIVE",
                user.userId(), null, null, 0L));
        ComplaintEmpathyScript stored = repository.findScriptByTitle(user.tenantId(), title).orElseThrow();
        auditService.record(user.tenantId(), user.userId(), "COMPLAINT_EMPATHY_SCRIPT_CREATE", RESOURCE_TYPE,
                stored.id().toString(), null, snapshot(stored), reason);
        idempotencyService.recordSuccess(user.tenantId(), CREATE_SCOPE, idempotencyKey, RESOURCE_TYPE,
                stored.id().toString(), payload);
        return ComplaintEmpathyScriptResponse.from(stored);
    }

    @Transactional
    public ComplaintEmpathyScriptResponse update(CurrentUser user, long id,
                                                  UpdateComplaintEmpathyScriptRequest request,
                                                  String idempotencyKey) {
        accessService.require(user, "complaint:config");
        String title = masker.required(request.title(), "话术标题", 64);
        String script = masker.requiredMultiline(request.script(), "话术内容", 2000);
        String reason = masker.requiredMasked(request.reason(), "修改理由", 1000);
        String payload = payload(id, request.version(), title, script, reason);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), UPDATE_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return replayScript(user, id);
        }
        ComplaintEmpathyScript before = repository.findScriptByIdForUpdate(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("话术不存在"));
        requireVersion(before.version(), request.version());
        if (!"ACTIVE".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "话术已停用");
        }
        repository.findScriptByTitle(user.tenantId(), title)
                .filter(other -> !other.id().equals(id))
                .ifPresent(other -> {
                    throw new BusinessException(ErrorCode.CONFLICT, "话术标题已存在");
                });
        if (repository.updateScript(user.tenantId(), id, request.version(), title, script) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "话术已变化，请刷新后重试");
        }
        ComplaintEmpathyScript after = script(user, id);
        auditService.record(user.tenantId(), user.userId(), "COMPLAINT_EMPATHY_SCRIPT_UPDATE", RESOURCE_TYPE,
                Long.toString(id), snapshot(before), snapshot(after), reason);
        idempotencyService.recordSuccess(user.tenantId(), UPDATE_SCOPE, idempotencyKey, RESOURCE_TYPE,
                Long.toString(id), payload);
        return ComplaintEmpathyScriptResponse.from(after);
    }

    @Transactional
    public ComplaintEmpathyScriptResponse disable(CurrentUser user, long id, long version, String reasonInput,
                                                   String idempotencyKey) {
        accessService.require(user, "complaint:config");
        String reason = masker.requiredMasked(reasonInput, "停用理由", 1000);
        String payload = payload(id, version, reason);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), DISABLE_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return replayScript(user, id);
        }
        ComplaintEmpathyScript before = repository.findScriptByIdForUpdate(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("话术不存在"));
        requireVersion(before.version(), version);
        if (!"ACTIVE".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "话术已停用");
        }
        if (repository.disableScript(user.tenantId(), id, version) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "话术已变化，请刷新后重试");
        }
        ComplaintEmpathyScript after = script(user, id);
        auditService.record(user.tenantId(), user.userId(), "COMPLAINT_EMPATHY_SCRIPT_DISABLE", RESOURCE_TYPE,
                Long.toString(id), snapshot(before), snapshot(after), reason);
        idempotencyService.recordSuccess(user.tenantId(), DISABLE_SCOPE, idempotencyKey, RESOURCE_TYPE,
                Long.toString(id), payload);
        return ComplaintEmpathyScriptResponse.from(after);
    }

    private ComplaintEmpathyScriptResponse replayScript(CurrentUser user, long id) {
        return ComplaintEmpathyScriptResponse.from(script(user, id));
    }

    private ComplaintEmpathyScript script(CurrentUser user, long id) {
        return repository.findScriptById(user.tenantId(), id)
                .orElseThrow(() -> accessService.notFound("话术不存在"));
    }

    private void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw new BusinessException(ErrorCode.CONFLICT, "话术已变化，请刷新后重试");
        }
    }

    private void requireResource(IdempotencyRecord record, long id) {
        if (!Long.toString(id).equals(record.getResourceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果资源不一致");
        }
    }

    private long resourceId(IdempotencyRecord record) {
        try {
            return Long.parseLong(record.getResourceId());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果资源无效");
        }
    }

    private Map<String, Object> snapshot(ComplaintEmpathyScript script) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", script.title());
        snapshot.put("script", masker.mask(script.script(), 2000));
        snapshot.put("status", script.status());
        snapshot.put("version", script.version());
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
