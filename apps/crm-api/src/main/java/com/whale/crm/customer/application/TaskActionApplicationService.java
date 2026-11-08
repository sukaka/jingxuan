package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.ClaimTaskRequest;
import com.whale.crm.customer.api.dto.CompleteTaskRequest;
import com.whale.crm.customer.api.dto.ReassignTaskRequest;
import com.whale.crm.customer.api.dto.ReturnTaskRequest;
import com.whale.crm.customer.api.dto.TaskActionItemResponse;
import com.whale.crm.customer.api.dto.TaskItemResponse;
import com.whale.crm.customer.domain.CrmTask;
import com.whale.crm.customer.domain.TaskActionLog;
import com.whale.crm.customer.domain.TaskRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TaskActionApplicationService {

    private static final String CLAIM = "CLAIM";
    private static final String REASSIGN = "REASSIGN";
    private static final String RETURN = "RETURN";
    private static final String COMPLETE = "COMPLETE";

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final CustomerScopeSupport scopeSupport;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public TaskActionApplicationService(TaskRepository taskRepository, UserRepository userRepository,
                                        CustomerScopeSupport scopeSupport, AuditService auditService,
                                        IdempotencyService idempotencyService, ObjectMapper objectMapper,
                                        PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.scopeSupport = scopeSupport;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<TaskActionItemResponse> actions(long tenantId, CurrentUser user, long taskId) {
        requireVisible(tenantId, user, taskId);
        return taskRepository.findActions(tenantId, taskId).stream().map(this::toActionResponse).toList();
    }

    public TaskItemResponse claim(long tenantId, CurrentUser user, long taskId,
                                  ClaimTaskRequest request, String idempotencyKey) {
        return write(tenantId, user, taskId, CLAIM, request.version(), null, null, idempotencyKey);
    }

    public TaskItemResponse reassign(long tenantId, CurrentUser user, long taskId,
                                     ReassignTaskRequest request, String idempotencyKey) {
        return write(tenantId, user, taskId, REASSIGN, request.version(), request.assigneeUserId(),
                request.reason(), idempotencyKey);
    }

    public TaskItemResponse returnToPool(long tenantId, CurrentUser user, long taskId,
                                         ReturnTaskRequest request, String idempotencyKey) {
        return write(tenantId, user, taskId, RETURN, request.version(), null, request.reason(), idempotencyKey);
    }

    public TaskItemResponse complete(long tenantId, CurrentUser user, long taskId,
                                     CompleteTaskRequest request, String idempotencyKey) {
        return write(tenantId, user, taskId, COMPLETE, request.version(), null, null, idempotencyKey);
    }

    private TaskItemResponse write(long tenantId, CurrentUser user, long taskId, String action, long version,
                                   Long assigneeUserId, String reason, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        String scope = scope(action);
        String payload = canonical(new ActionCanonical(taskId, version, assigneeUserId, reason));
        IdempotencyRecord replay = lookup(tenantId, scope, key, payload);
        if (replay != null) {
            return replayResponse(tenantId, user, taskId, replay);
        }
        try {
            return transactionTemplate.execute(status -> doWrite(tenantId, user, taskId, action, version,
                    assigneeUserId, normalizeReason(reason), scope, key, payload));
        } catch (DuplicateKeyException ex) {
            return replayAfterConflict(tenantId, user, taskId, scope, key, payload);
        }
    }

    private TaskItemResponse doWrite(long tenantId, CurrentUser user, long taskId, String action, long version,
                                     Long assigneeUserId, String reason, String scope, String key, String payload) {
        CrmTask task = lockVisible(tenantId, user, taskId);
        IdempotencyRecord replay = lookup(tenantId, scope, key, payload);
        if (replay != null) {
            requireReplayResource(replay, taskId);
            return toResponse(task);
        }
        if (!Objects.equals(task.getVersion(), version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        Map<String, Object> beforeSnapshot = taskSnapshot(task);
        String fromStatus = task.getStatus();
        Long fromAssignee = task.getAssigneeUserId();
        Instant now = Instant.now();
        switch (action) {
            case CLAIM -> applyClaim(task, user, now);
            case REASSIGN -> applyReassign(tenantId, task, user, assigneeUserId, now);
            case RETURN -> applyReturn(task, user);
            case COMPLETE -> applyComplete(task, user, now);
            default -> throw new IllegalArgumentException("未知任务动作");
        }
        if (taskRepository.applyAction(task, fromStatus) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务状态或版本已变化，请刷新后重试");
        }
        CrmTask after = taskRepository.findById(tenantId, taskId).orElseThrow();
        TaskActionLog log = new TaskActionLog();
        log.setTenantId(tenantId);
        log.setTaskId(taskId);
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(after.getStatus());
        log.setFromAssigneeUserId(fromAssignee);
        log.setToAssigneeUserId(after.getAssigneeUserId());
        log.setActorUserId(user.userId());
        log.setReason(reason);
        log.setIdempotencyKey(key);
        taskRepository.insertAction(log);
        auditService.record(tenantId, user.userId(), "TASK_" + action, "TASK", Long.toString(taskId),
                beforeSnapshot, taskSnapshot(after), reason);
        idempotencyService.recordSuccess(tenantId, scope, key, "TASK", Long.toString(taskId), payload);
        return toResponse(after);
    }

    private void applyClaim(CrmTask task, CurrentUser user, Instant now) {
        requireStatus(task, CrmTask.STATUS_OPEN, "只有OPEN任务可接手");
        task.setAssigneeUserId(user.userId());
        task.setStatus(CrmTask.STATUS_PROCESSING);
        task.setClaimedAt(now);
        task.setCompletedAt(null);
    }

    private void applyReassign(long tenantId, CrmTask task, CurrentUser user, Long assigneeUserId, Instant now) {
        if (!user.roles().contains("ADMIN") && !user.roles().contains("MANAGER_LEAD")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有主管或管理员可转派任务");
        }
        if (!task.isOpen()) {
            throw new BusinessException(ErrorCode.CONFLICT, "已终结任务不能转派");
        }
        SysUser target = userRepository.findById(tenantId, assigneeUserId)
                .filter(SysUser::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "负责人不存在或已停用"));
        if (!"EMPLOYEE".equals(target.getUserType())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "负责人必须是内部员工");
        }
        if (!user.roles().contains("ADMIN")
                && (user.teamId() == null || !Objects.equals(user.teamId(), target.getTeamId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "主管只能转派给本组成员");
        }
        task.setAssigneeUserId(target.getId());
        task.setTaskTeamId(target.getTeamId());
        task.setStatus(CrmTask.STATUS_PROCESSING);
        task.setClaimedAt(now);
        task.setCompletedAt(null);
    }

    private void applyReturn(CrmTask task, CurrentUser user) {
        requireStatus(task, CrmTask.STATUS_PROCESSING, "只有PROCESSING任务可退回");
        requireAssigneeOrSupervisor(task, user);
        task.setAssigneeUserId(null);
        task.setStatus(CrmTask.STATUS_OPEN);
        task.setClaimedAt(null);
        task.setCompletedAt(null);
    }

    private void applyComplete(CrmTask task, CurrentUser user, Instant now) {
        requireStatus(task, CrmTask.STATUS_PROCESSING, "只有PROCESSING任务可完成");
        requireAssigneeOrSupervisor(task, user);
        task.setStatus(CrmTask.STATUS_DONE);
        task.setCompletedAt(now);
    }

    private void requireAssigneeOrSupervisor(CrmTask task, CurrentUser user) {
        if (Objects.equals(task.getAssigneeUserId(), user.userId()) || user.roles().contains("ADMIN")) {
            return;
        }
        if (user.roles().contains("MANAGER_LEAD") && user.teamId() != null
                && Objects.equals(user.teamId(), task.getTaskTeamId())) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "普通管家只能退回或完成本人任务");
    }

    private void requireStatus(CrmTask task, String expected, String message) {
        if (!expected.equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, message + "（当前 " + task.getStatus() + "）");
        }
    }

    private CrmTask lockVisible(long tenantId, CurrentUser user, long taskId) {
        CrmTask task = taskRepository.findByIdForUpdate(tenantId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "任务不存在或不可见"));
        requireVisible(user, task);
        return task;
    }

    private CrmTask requireVisible(long tenantId, CurrentUser user, long taskId) {
        CrmTask task = taskRepository.findById(tenantId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "任务不存在或不可见"));
        requireVisible(user, task);
        return task;
    }

    private void requireVisible(CurrentUser user, CrmTask task) {
        DataScope scope = scopeSupport.scope(user);
        boolean visible = switch (scope.type()) {
            case ALL -> true;
            case TEAM -> Objects.equals(task.getAssigneeUserId(), user.userId())
                    || task.getTaskTeamId() != null && scope.teamIds().contains(task.getTaskTeamId());
            case SELF -> Objects.equals(task.getAssigneeUserId(), user.userId())
                    || task.getAssigneeUserId() == null && user.teamId() != null
                    && Objects.equals(task.getTaskTeamId(), user.teamId());
            default -> false;
        };
        if (!visible) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在或不可见");
        }
    }

    private TaskItemResponse replayResponse(long tenantId, CurrentUser user, long taskId, IdempotencyRecord record) {
        requireReplayResource(record, taskId);
        return toResponse(requireVisible(tenantId, user, taskId));
    }

    private TaskItemResponse replayAfterConflict(long tenantId, CurrentUser user, long taskId, String scope,
                                                 String key, String payload) {
        return transactionTemplate.execute(status -> {
            IdempotencyRecord record = lookup(tenantId, scope, key, payload);
            if (record == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "任务动作冲突且没有已提交幂等结果");
            }
            return replayResponse(tenantId, user, taskId, record);
        });
    }

    private void requireReplayResource(IdempotencyRecord record, long taskId) {
        if (!"TASK".equals(record.getResourceType()) || !Long.toString(taskId).equals(record.getResourceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果不属于该任务");
        }
    }

    private IdempotencyRecord lookup(long tenantId, String scope, String key, String payload) {
        return idempotencyService.lookup(tenantId, scope, key, payload).orElse(null);
    }

    private String scope(String action) {
        return "task.action." + action.toLowerCase(java.util.Locale.ROOT);
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供1至128字符的幂等键");
        }
        return key.strip();
    }

    private String canonical(ActionCanonical value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("任务动作请求规范化失败", ex);
        }
    }

    private String normalizeReason(String reason) {
        return reason == null ? null : reason.strip();
    }

    private Map<String, Object> taskSnapshot(CrmTask task) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", task.getId());
        snapshot.put("customerId", task.getCustomerId());
        snapshot.put("assigneeUserId", task.getAssigneeUserId());
        snapshot.put("originalAssigneeUserId", task.getOriginalAssigneeUserId());
        snapshot.put("taskTeamId", task.getTaskTeamId());
        snapshot.put("taskType", task.getTaskType());
        snapshot.put("priority", task.getPriority());
        snapshot.put("status", task.getStatus());
        snapshot.put("title", task.getTitle());
        snapshot.put("dueAt", task.getDueAt());
        snapshot.put("claimedAt", task.getClaimedAt());
        snapshot.put("completedAt", task.getCompletedAt());
        snapshot.put("version", task.getVersion());
        return snapshot;
    }

    private TaskItemResponse toResponse(CrmTask task) {
        return new TaskItemResponse(task.getId(), task.getCustomerId(), task.getAssigneeUserId(),
                task.getOriginalAssigneeUserId(), task.getTaskTeamId(), task.getTaskType(), task.getPriority(),
                task.getStatus(), task.getTitle(), task.getDescription(), task.getDueAt(), task.getClaimedAt(),
                task.getCompletedAt(), task.getCreatedAt(), task.getVersion());
    }

    private TaskActionItemResponse toActionResponse(TaskActionLog log) {
        return new TaskActionItemResponse(log.getId(), log.getTaskId(), log.getAction(), log.getFromStatus(),
                log.getToStatus(), log.getFromAssigneeUserId(), log.getToAssigneeUserId(), log.getActorUserId(),
                log.getReason(), log.getCreatedAt());
    }

    private record ActionCanonical(long taskId, long version, Long assigneeUserId, String reason) {
    }
}
