package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.ClaimTaskRequest;
import com.whale.crm.customer.api.dto.CompleteTaskRequest;
import com.whale.crm.customer.api.dto.CreateTaskRequest;
import com.whale.crm.customer.api.dto.ReassignTaskRequest;
import com.whale.crm.customer.api.dto.ReturnTaskRequest;
import com.whale.crm.customer.api.dto.TaskActionItemResponse;
import com.whale.crm.customer.api.dto.TaskItemResponse;
import com.whale.crm.customer.api.dto.UpdateTaskRequest;
import com.whale.crm.customer.application.TaskActionApplicationService;
import com.whale.crm.customer.application.TaskApplicationService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 回访/待办任务（task:read/write）。创建即分配本人；assigneeId 查询参数只允许收窄。
 */
@Validated
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskApplicationService taskService;
    private final TaskActionApplicationService actionService;

    public TaskController(TaskApplicationService taskService, TaskActionApplicationService actionService) {
        this.taskService = taskService;
        this.actionService = actionService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('task:read')")
    public ApiResponse<PageResponse<TaskItemResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false)
            @Pattern(regexp = "OPEN|PROCESSING|DONE|CANCELED", message = "status 仅支持 OPEN/PROCESSING/DONE/CANCELED") String status,
            HttpServletRequest request) {
        return ApiResponse.ok(taskService.search(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), assigneeId, status, page, size), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('task:write')")
    public ResponseEntity<ApiResponse<TaskItemResponse>> create(
            @Valid @RequestBody CreateTaskRequest body, HttpServletRequest request) {
        TaskItemResponse created = taskService.create(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('task:write')")
    public ApiResponse<TaskItemResponse> update(@PathVariable long id,
                                                @Valid @RequestBody UpdateTaskRequest body,
                                                HttpServletRequest request) {
        return ApiResponse.ok(taskService.update(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PatchMapping("/{id}/assignee")
    @PreAuthorize("hasAuthority('task:write')")
    public ApiResponse<TaskItemResponse> updateAssignee(@PathVariable long id,
            @Valid @RequestBody com.whale.crm.customer.api.dto.UpdateTaskAssigneeRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(taskService.updateAssignee(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, idempotencyKey), requestId(request));
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasAuthority('task:write')")
    public ApiResponse<TaskItemResponse> claim(@PathVariable long id,
            @Valid @RequestBody ClaimTaskRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(actionService.claim(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, idempotencyKey), requestId(request));
    }

    @PostMapping("/{id}/reassign")
    @PreAuthorize("hasAuthority('task:write')")
    public ApiResponse<TaskItemResponse> reassign(@PathVariable long id,
            @Valid @RequestBody ReassignTaskRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(actionService.reassign(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, idempotencyKey), requestId(request));
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAuthority('task:write')")
    public ApiResponse<TaskItemResponse> returnToPool(@PathVariable long id,
            @Valid @RequestBody ReturnTaskRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(actionService.returnToPool(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, idempotencyKey), requestId(request));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('task:write')")
    public ApiResponse<TaskItemResponse> complete(@PathVariable long id,
            @Valid @RequestBody CompleteTaskRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(actionService.complete(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, idempotencyKey), requestId(request));
    }

    @GetMapping("/{id}/actions")
    @PreAuthorize("hasAuthority('task:read')")
    public ApiResponse<List<TaskActionItemResponse>> actions(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(actionService.actions(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> missingRequestHeader(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_FAILED.name(),
                "任务动作写请求必须提供幂等键", null, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
