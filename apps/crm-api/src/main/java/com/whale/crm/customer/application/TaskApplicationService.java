package com.whale.crm.customer.application;

import com.whale.crm.customer.api.dto.CreateTaskRequest;
import com.whale.crm.customer.api.dto.TaskItemResponse;
import com.whale.crm.customer.api.dto.UpdateTaskRequest;
import com.whale.crm.customer.domain.CrmTask;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.Followup;
import com.whale.crm.customer.domain.TaskDutySummary;
import com.whale.crm.customer.domain.TaskRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 回访/待办任务（crm_task）。手工创建分配给本人；跟进规则按客户负责人创建。
 * 列表范围 ALL/TEAM/SELF 由客户数据范围收敛，assigneeId 参数只允许收窄。
 * 通用更新不改变状态，接手/转派/退回/完成统一走动作服务。
 */
@Service
public class TaskApplicationService {

    private final TaskRepository taskRepository;
    private final CustomerScopeSupport scopeSupport;
    private final AuditService auditService;
    private final TaskActionApplicationService actionService;
    private final CurrentUserService currentUserService;

    public TaskApplicationService(TaskRepository taskRepository,
                                  CustomerScopeSupport scopeSupport,
                                  AuditService auditService,
                                  TaskActionApplicationService actionService,
                                  CurrentUserService currentUserService) {
        this.taskRepository = taskRepository;
        this.scopeSupport = scopeSupport;
        this.auditService = auditService;
        this.actionService = actionService;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskItemResponse> search(long tenantId, CurrentUser user, Long assigneeId,
                                                 String status, int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        List<Long> teamIds = scope.type() == DataScope.ScopeType.SELF && user.teamId() != null
                ? List.of(user.teamId()) : query.teamIds();
        int offset = (page - 1) * size;
        List<TaskItemResponse> items = taskRepository
                .search(tenantId, query.scopeType(), teamIds, query.selfId(), assigneeId, status, offset, size)
                .stream().map(this::toResponse).toList();
        long total = taskRepository.countSearch(tenantId, query.scopeType(), teamIds, query.selfId(),
                assigneeId, status);
        return new PageResponse<>(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public TaskDutySummary dutySummary(long tenantId, CurrentUser user, Instant now, Instant dayEndExclusive) {
        if (now == null || dayEndExclusive == null || !now.isBefore(dayEndExclusive)) {
            throw new IllegalArgumentException("任务统计时间范围无效");
        }
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return TaskDutySummary.empty();
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        List<Long> teamIds = scope.type() == DataScope.ScopeType.SELF && user.teamId() != null
                ? List.of(user.teamId()) : query.teamIds();
        return taskRepository.dutySummary(tenantId, query.scopeType(), teamIds, query.selfId(), now, dayEndExclusive);
    }

    @Transactional
    public TaskItemResponse create(long tenantId, CurrentUser user, CreateTaskRequest request) {
        if (request.customerId() != null) {
            scopeSupport.requireVisible(tenantId, request.customerId(), user);
        }
        CrmTask task = new CrmTask();
        task.setTenantId(tenantId);
        task.setCustomerId(request.customerId());
        task.setAssigneeUserId(user.userId());
        task.setOriginalAssigneeUserId(user.userId());
        task.setTaskTeamId(user.teamId());
        task.setTaskType(request.taskType());
        task.setPriority(request.priority() != null ? request.priority() : CrmTask.PRIORITY_MEDIUM);
        task.setStatus(CrmTask.STATUS_OPEN);
        task.setTitle(request.title());
        task.setDescription(blankToNull(request.description()));
        task.setDueAt(request.dueAt());
        task.setSourceType(CrmTask.SOURCE_MANUAL);
        taskRepository.insert(task);

        CrmTask persisted = taskRepository.findById(tenantId, task.getId()).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_TASK_CREATE, "TASK",
                Long.toString(task.getId()), null, taskSnapshot(persisted), null);
        return toResponse(persisted);
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public TaskItemResponse syncFromFollowup(long tenantId, CurrentUser user, Followup followup, Customer customer) {
        if (followup.getNextFollowupAt() == null || followup.isDeleted()) {
            return null;
        }
        scopeSupport.requireVisible(tenantId, customer.getId(), user);
        long assigneeUserId = currentUserService.loadById(followup.getOwnerStaffId())
                .filter(owner -> owner.tenantId() == tenantId)
                .map(CurrentUser::userId)
                .orElse(user.userId());
        String sourceRef = CrmTask.SOURCE_FOLLOWUP + ":" + followup.getId();
        String customerName = java.util.stream.Stream.of(customer.getDisplayName(), customer.getNickname(), customer.getCustomerNo())
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse("客户");
        String project = blankToNull(followup.getIntentProject());
        String title = "回访：" + customerName + (project == null ? "" : " · " + project);

        CrmTask task = new CrmTask();
        task.setTenantId(tenantId);
        task.setCustomerId(customer.getId());
        task.setAssigneeUserId(assigneeUserId);
        task.setOriginalAssigneeUserId(assigneeUserId);
        task.setTaskTeamId(customer.getTeamId());
        task.setTaskType("FOLLOWUP");
        task.setPriority(CrmTask.PRIORITY_MEDIUM);
        task.setStatus(CrmTask.STATUS_OPEN);
        task.setTitle(title.length() <= 255 ? title : title.substring(0, 255));
        task.setDescription(followup.getSummary() != null && !followup.getSummary().isBlank()
                ? followup.getSummary() : blankToNull(followup.getBlockerNote()));
        task.setDueAt(followup.getNextFollowupAt());
        task.setSourceType(CrmTask.SOURCE_FOLLOWUP);
        task.setSourceRef(sourceRef);

        boolean existed = taskRepository.findBySource(tenantId, CrmTask.SOURCE_FOLLOWUP, sourceRef).isPresent();
        taskRepository.insertAutomatic(task);
        CrmTask persisted = taskRepository.findBySource(tenantId, CrmTask.SOURCE_FOLLOWUP, sourceRef).orElseThrow();
        if (!existed) {
            auditService.record(tenantId, user.userId(), AuditService.ACTION_TASK_CREATE, "TASK",
                    Long.toString(persisted.getId()), null, taskSnapshot(persisted), "根据下次跟进时间自动创建");
            return toResponse(persisted);
        }
        if (CrmTask.STATUS_OPEN.equals(persisted.getStatus())
                && !Objects.equals(persisted.getDueAt(), followup.getNextFollowupAt())) {
            Map<String, Object> beforeSnapshot = taskSnapshot(persisted);
            int updated = taskRepository.updateAutomaticDueAt(tenantId, persisted.getId(), followup.getNextFollowupAt());
            if (updated == 1) {
                CrmTask after = taskRepository.findById(tenantId, persisted.getId()).orElseThrow();
                auditService.record(tenantId, user.userId(), AuditService.ACTION_TASK_UPDATE, "TASK",
                        Long.toString(after.getId()), beforeSnapshot, taskSnapshot(after), "根据下次跟进时间自动调整");
                return toResponse(after);
            }
        }
        return toResponse(persisted);
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public long createFromAi(long tenantId, CurrentUser user, long customerId, String sourceRef,
                             String title, String description, java.time.Instant dueAt) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        CrmTask task = new CrmTask();
        task.setTenantId(tenantId);
        task.setCustomerId(customerId);
        task.setAssigneeUserId(user.userId());
        task.setOriginalAssigneeUserId(user.userId());
        task.setTaskTeamId(user.teamId());
        task.setTaskType("FOLLOWUP");
        task.setPriority(CrmTask.PRIORITY_MEDIUM);
        task.setStatus(CrmTask.STATUS_OPEN);
        task.setTitle(title);
        task.setDescription(blankToNull(description));
        task.setDueAt(dueAt);
        task.setSourceType("AI");
        task.setSourceRef(sourceRef);
        taskRepository.insert(task);
        CrmTask persisted = taskRepository.findById(tenantId, task.getId()).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_TASK_CREATE, "TASK",
                Long.toString(task.getId()), null, taskSnapshot(persisted), "AI 建议经人工确认后生成");
        return task.getId();
    }

    @Transactional
    public TaskItemResponse update(long tenantId, CurrentUser user, long taskId, UpdateTaskRequest request) {
        CrmTask existing = taskRepository.findById(tenantId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "任务不存在或不可见"));
        if (!isVisible(tenantId, user, existing)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在或不可见");
        }
        requireEditable(tenantId, user, existing);
        if (!Objects.equals(existing.getVersion(), request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        Map<String, Object> beforeSnapshot = taskSnapshot(existing);
        if (request.status() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "通用PATCH禁止状态变更，请使用任务动作接口");
        }
        existing.setTaskType(request.taskType() != null ? request.taskType() : existing.getTaskType());
        existing.setPriority(request.priority() != null ? request.priority() : existing.getPriority());
        existing.setTitle(request.title() != null ? request.title() : existing.getTitle());
        existing.setDescription(request.description() != null ? blankToNull(request.description()) : existing.getDescription());
        existing.setDueAt(request.dueAt() != null ? request.dueAt() : existing.getDueAt());
        int rows = taskRepository.update(existing);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        CrmTask after = taskRepository.findById(tenantId, taskId).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_TASK_UPDATE, "TASK",
                Long.toString(taskId), beforeSnapshot, taskSnapshot(after), null);
        return toResponse(after);
    }

    public TaskItemResponse updateAssignee(long tenantId, CurrentUser user, long taskId,
                                           com.whale.crm.customer.api.dto.UpdateTaskAssigneeRequest request,
                                           String idempotencyKey) {
        return actionService.reassign(tenantId, user, taskId,
                new com.whale.crm.customer.api.dto.ReassignTaskRequest(
                        request.assigneeUserId(), request.reason(), request.version()), idempotencyKey);
    }

    private boolean isVisible(long tenantId, CurrentUser user, CrmTask task) {
        DataScope scope = scopeSupport.scope(user);
        return switch (scope.type()) {
            case ALL -> true;
            case SELF -> Objects.equals(task.getAssigneeUserId(), user.userId())
                    || task.getAssigneeUserId() == null && user.teamId() != null
                    && Objects.equals(task.getTaskTeamId(), user.teamId());
            case TEAM -> Objects.equals(task.getAssigneeUserId(), user.userId())
                    || task.getTaskTeamId() != null && scope.teamIds().contains(task.getTaskTeamId());
            default -> false;
        };
    }

    private void requireEditable(long tenantId, CurrentUser user, CrmTask task) {
        if (Objects.equals(task.getAssigneeUserId(), user.userId()) || user.roles().contains("ADMIN")) {
            return;
        }
        if (user.roles().contains("MANAGER_LEAD") && user.teamId() != null
                && Objects.equals(task.getTaskTeamId(), user.teamId())) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务负责人或同组主管可修改");
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
        return snapshot;
    }

    private TaskItemResponse toResponse(CrmTask task) {
        return new TaskItemResponse(task.getId(), task.getCustomerId(), task.getAssigneeUserId(),
                task.getOriginalAssigneeUserId(), task.getTaskTeamId(), task.getTaskType(), task.getPriority(),
                task.getStatus(), task.getTitle(), task.getDescription(), task.getDueAt(), task.getClaimedAt(),
                task.getCompletedAt(), task.getCreatedAt(), task.getVersion());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
