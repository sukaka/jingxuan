package com.whale.crm.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.notification.api.dto.CreateWecomMappingRequest;
import com.whale.crm.notification.api.dto.UpdateWecomMappingRequest;
import com.whale.crm.notification.api.dto.WecomMappingResponse;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.notification.domain.WecomMapping;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WecomMappingApplicationService {
    private static final String CREATE_SCOPE = "notification.wecom_mapping.create";
    private static final String UPDATE_SCOPE = "notification.wecom_mapping.update";

    private final NotificationRepository repository;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public WecomMappingApplicationService(NotificationRepository repository, IdempotencyService idempotencyService,
                                          AuditService auditService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<WecomMappingResponse> list(long tenantId, CurrentUser user) {
        requireAnyAccess(tenantId, user, "wecom:mapping:manage", "notification:delivery:read");
        return repository.findWecomMappings(tenantId).stream().map(WecomMappingResponse::from).toList();
    }

    @Transactional
    public WecomMappingResponse create(long tenantId, CurrentUser user, CreateWecomMappingRequest request,
                                       String idempotencyKey) {
        requireAccess(tenantId, user, "wecom:mapping:manage");
        WecomMapping mapping = new WecomMapping(0, tenantId, request.corpId().strip(), request.wecomUserid().strip(),
                request.staffUserId(), false, request.messageEnabled(), 0);
        requireActiveEmployee(tenantId, mapping.staffUserId());
        String payload = payloadOf(mapping);
        var replay = idempotencyService.lookup(tenantId, CREATE_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            return response(tenantId, Long.parseLong(replay.orElseThrow().getResourceId()));
        }
        long generatedId;
        try {
            generatedId = repository.insertWecomMapping(mapping);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "该员工或企微成员映射已存在");
        }
        WecomMapping persisted = requireMapping(tenantId, generatedId);
        idempotencyService.recordSuccess(tenantId, CREATE_SCOPE, idempotencyKey, "WECOM_MAPPING",
                Long.toString(persisted.id()), payload);
        auditService.record(tenantId, user.userId(), "WECOM_MAPPING_CREATE", "WECOM_MAPPING",
                Long.toString(persisted.id()), null, snapshot(persisted), request.reason().strip());
        return WecomMappingResponse.from(persisted);
    }

    @Transactional
    public WecomMappingResponse update(long tenantId, CurrentUser user, long id, UpdateWecomMappingRequest request,
                                       String idempotencyKey) {
        requireAccess(tenantId, user, "wecom:mapping:manage");
        WecomMapping requested = new WecomMapping(id, tenantId, request.corpId().strip(), request.wecomUserid().strip(),
                request.staffUserId(), false, request.messageEnabled(), request.version());
        String payload = payloadOf(requested);
        var replay = idempotencyService.lookup(tenantId, UPDATE_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            if (!Long.toString(id).equals(replay.orElseThrow().getResourceId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "幂等结果与请求资源不一致");
            }
            return response(tenantId, id);
        }
        WecomMapping before = repository.findWecomMappingForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "企微通知映射不存在"));
        if (before.version() != request.version()) {
            throw new BusinessException(ErrorCode.CONFLICT, "映射已被他人修改，请刷新后重试");
        }
        if (before.archiveEnabled() && (!before.corpId().equals(requested.corpId())
                || !before.wecomUserid().equals(requested.wecomUserid())
                || before.staffUserId() != requested.staffUserId())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "已启用归档或登录同步的企微映射不允许修改企业ID、企微成员ID或关联员工，仅可调整通知开关");
        }
        requireActiveEmployee(tenantId, requested.staffUserId());
        try {
            if (repository.updateWecomMapping(requested) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "映射已被他人修改，请刷新后重试");
            }
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "该员工或企微成员映射已存在");
        }
        WecomMapping after = requireMapping(tenantId, id);
        auditService.record(tenantId, user.userId(), "WECOM_MAPPING_UPDATE", "WECOM_MAPPING",
                Long.toString(id), snapshot(before), snapshot(after), request.reason().strip());
        idempotencyService.recordSuccess(tenantId, UPDATE_SCOPE, idempotencyKey, "WECOM_MAPPING",
                Long.toString(id), payload);
        return WecomMappingResponse.from(after);
    }

    private void requireActiveEmployee(long tenantId, long staffUserId) {
        if (!repository.employeeActive(tenantId, staffUserId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "员工不存在、跨租户或已停用");
        }
    }

    private WecomMapping requireMapping(long tenantId, long id) {
        return repository.findWecomMapping(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "企微通知映射不存在"));
    }

    private WecomMappingResponse response(long tenantId, long id) {
        return WecomMappingResponse.from(requireMapping(tenantId, id));
    }

    private String payloadOf(WecomMapping mapping) {
        try {
            return objectMapper.writeValueAsString(Map.of("corpId", mapping.corpId(), "wecomUserid",
                    mapping.wecomUserid(), "staffUserId", mapping.staffUserId(), "messageEnabled",
                    mapping.messageEnabled(), "version", mapping.version()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法计算映射请求哈希", ex);
        }
    }

    private Map<String, Object> snapshot(WecomMapping mapping) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("corpId", mapping.corpId());
        snapshot.put("wecomUserid", mapping.wecomUserid());
        snapshot.put("staffUserId", mapping.staffUserId());
        snapshot.put("archiveEnabled", mapping.archiveEnabled());
        snapshot.put("messageEnabled", mapping.messageEnabled());
        snapshot.put("version", mapping.version());
        return snapshot;
    }

    private void requireAccess(long tenantId, CurrentUser user, String permission) {
        if (tenantId != user.tenantId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "跨租户访问被拒绝");
        }
        if (!user.permissions().contains(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有企微通知映射管理权限");
        }
    }

    private void requireAnyAccess(long tenantId, CurrentUser user, String... permissions) {
        if (tenantId != user.tenantId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "跨租户访问被拒绝");
        }
        for (String permission : permissions) {
            if (user.permissions().contains(permission)) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "没有企微通知映射管理权限");
    }
}
