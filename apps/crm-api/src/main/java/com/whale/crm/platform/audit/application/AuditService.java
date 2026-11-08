package com.whale.crm.platform.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.audit.api.dto.AuditLogResponse;
import com.whale.crm.platform.audit.domain.AuditLog;
import com.whale.crm.platform.audit.domain.AuditLogRepository;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 审计服务：登录、权限变更、字典写、用户管理写等敏感动作的不可覆盖日志。
 * 快照由调用方构造（白名单字段 Map），本服务只负责序列化与追加，不猜测业务字段。
 * traceId 取 MDC requestId，clientIp 取当前请求；与业务写同事务，保证事实与审计同生共死。
 */
@Service
public class AuditService {

    // 动作编码（稳定字符串，供筛选与对账）
    public static final String ACTION_LOGIN = "LOGIN";
    public static final String ACTION_LOGIN_FAILED = "LOGIN_FAILED";
    public static final String ACTION_LOGOUT = "LOGOUT";
    public static final String ACTION_USER_CREATE = "USER_CREATE";
    public static final String ACTION_USER_UPDATE = "USER_UPDATE";
    public static final String ACTION_USER_DISABLE = "USER_DISABLE";
    public static final String ACTION_USER_ROLE_ASSIGN = "USER_ROLE_ASSIGN";
    public static final String ACTION_ROLE_CREATE = "ROLE_CREATE";
    public static final String ACTION_ROLE_PERMISSION_UPDATE = "ROLE_PERMISSION_UPDATE";
    public static final String ACTION_DICTIONARY_ITEM_CREATE = "DICTIONARY_ITEM_CREATE";
    public static final String ACTION_DICTIONARY_ITEM_UPDATE = "DICTIONARY_ITEM_UPDATE";
    public static final String ACTION_DICTIONARY_ITEM_DISABLE = "DICTIONARY_ITEM_DISABLE";
    public static final String ACTION_CUSTOMER_CREATE = "CUSTOMER_CREATE";
    public static final String ACTION_CUSTOMER_UPDATE = "CUSTOMER_UPDATE";
    public static final String ACTION_CUSTOMER_TAGS_UPDATE = "CUSTOMER_TAGS_UPDATE";
    public static final String ACTION_CUSTOMER_POINTS_ADJUST = "CUSTOMER_POINTS_ADJUST";
    public static final String ACTION_CUSTOMER_TRANSFER_REQUEST = "CUSTOMER_TRANSFER_REQUEST";
    public static final String ACTION_CUSTOMER_TRANSFER_APPROVE = "CUSTOMER_TRANSFER_APPROVE";
    public static final String ACTION_CUSTOMER_TRANSFER_REJECT = "CUSTOMER_TRANSFER_REJECT";
    public static final String ACTION_MERGE_CASE_CREATE = "MERGE_CASE_CREATE";
    public static final String ACTION_MERGE_CANDIDATE_SCAN = "MERGE_CANDIDATE_SCAN";
    public static final String ACTION_MERGE_CASE_REVIEW = "MERGE_CASE_REVIEW";
    public static final String ACTION_MERGE_CASE_CONFIRM = "MERGE_CASE_CONFIRM";
    public static final String ACTION_MERGE_CASE_REJECT = "MERGE_CASE_REJECT";
    public static final String ACTION_FOLLOWUP_CREATE = "FOLLOWUP_CREATE";
    public static final String ACTION_FOLLOWUP_UPDATE = "FOLLOWUP_UPDATE";
    public static final String ACTION_TASK_CREATE = "TASK_CREATE";
    public static final String ACTION_TASK_UPDATE = "TASK_UPDATE";

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(long tenantId, Long actorUserId, String actionCode, String targetType, String targetId,
                       Object beforeSnapshot, Object afterSnapshot, String reason) {
        AuditLog entry = new AuditLog();
        entry.setTenantId(tenantId);
        entry.setActorUserId(actorUserId);
        entry.setActionCode(actionCode);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setBeforeJson(toJson(beforeSnapshot));
        entry.setAfterJson(toJson(afterSnapshot));
        entry.setReason(reason);
        entry.setTraceId(MDC.get(RequestIdFilter.MDC_KEY));
        entry.setClientIp(currentClientIp());
        auditLogRepository.insert(entry);
    }

    /**
     * 独立事务写入：用于“业务失败本身也要留痕”的场景（如登录失败），不随后续异常回滚。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(long tenantId, Long actorUserId, String actionCode, String targetType,
                                  String targetId, Object beforeSnapshot, Object afterSnapshot, String reason) {
        record(tenantId, actorUserId, actionCode, targetType, targetId, beforeSnapshot, afterSnapshot, reason);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(long tenantId, Long actorUserId, String actionCode,
                                                 String targetType, int page, int size) {
        int offset = (page - 1) * size;
        List<AuditLogResponse> items = auditLogRepository
                .search(tenantId, actorUserId, actionCode, targetType, offset, size)
                .stream()
                .map(this::toResponse)
                .toList();
        long total = auditLogRepository.count(tenantId, actorUserId, actionCode, targetType);
        return new PageResponse<>(items, total, page, size);
    }

    private AuditLogResponse toResponse(AuditLogRepository.AuditLogRow row) {
        return new AuditLogResponse(
                row.getId(),
                row.getActorUserId(),
                row.getActorUsername(),
                row.getActionCode(),
                row.getTargetType(),
                row.getTargetId(),
                parseJson(row.getBeforeJson()),
                parseJson(row.getAfterJson()),
                row.getTraceId(),
                row.getClientIp(),
                row.getReason(),
                row.getCreatedAt());
    }

    private String toJson(Object snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            log.warn("审计快照序列化失败，已丢弃: {}", ex.getMessage());
            return null;
        }
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    private String currentClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
