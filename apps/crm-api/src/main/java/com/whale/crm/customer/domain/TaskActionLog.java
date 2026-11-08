package com.whale.crm.customer.domain;

import java.time.Instant;

public class TaskActionLog {

    private Long id;
    private Long tenantId;
    private Long taskId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private Long fromAssigneeUserId;
    private Long toAssigneeUserId;
    private Long actorUserId;
    private String reason;
    private String idempotencyKey;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public Long getFromAssigneeUserId() {
        return fromAssigneeUserId;
    }

    public void setFromAssigneeUserId(Long fromAssigneeUserId) {
        this.fromAssigneeUserId = fromAssigneeUserId;
    }

    public Long getToAssigneeUserId() {
        return toAssigneeUserId;
    }

    public void setToAssigneeUserId(Long toAssigneeUserId) {
        this.toAssigneeUserId = toAssigneeUserId;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(Long actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
