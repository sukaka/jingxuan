package com.whale.crm.customer.domain;

import java.time.Instant;

/**
 * 人工、跟进规则与 AI 待办（crm_task）。OPEN 接手/转派到 PROCESSING，PROCESSING 可退回 OPEN 或完成到 DONE。
 * 历史 CANCELED 与 DONE 均为终态；source_type 区分手工、跟进规则和 AI 来源。
 */
public class CrmTask {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_CANCELED = "CANCELED";
    public static final String PRIORITY_URGENT = "URGENT";

    public static final String PRIORITY_LOW = "LOW";
    public static final String PRIORITY_MEDIUM = "MEDIUM";
    public static final String PRIORITY_HIGH = "HIGH";

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_FOLLOWUP = "FOLLOWUP";

    private Long id;
    private Long tenantId;
    private Long customerId;
    private Long assigneeUserId;
    private Long originalAssigneeUserId;
    private Long taskTeamId;
    private String taskType;
    private String priority;
    private String status;
    private String title;
    private String description;
    private Instant dueAt;
    private String sourceType;
    private String sourceRef;
    private Instant claimedAt;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    public boolean isOpen() {
        return STATUS_OPEN.equals(status) || STATUS_PROCESSING.equals(status);
    }

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

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getAssigneeUserId() {
        return assigneeUserId;
    }

    public void setAssigneeUserId(Long assigneeUserId) {
        this.assigneeUserId = assigneeUserId;
    }

    public Long getOriginalAssigneeUserId() {
        return originalAssigneeUserId;
    }

    public void setOriginalAssigneeUserId(Long originalAssigneeUserId) {
        this.originalAssigneeUserId = originalAssigneeUserId;
    }

    public Long getTaskTeamId() {
        return taskTeamId;
    }

    public void setTaskTeamId(Long taskTeamId) {
        this.taskTeamId = taskTeamId;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
