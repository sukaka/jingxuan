package com.whale.crm.platform.audit.domain;

import java.util.List;

/**
 * crm_audit_log 存取：只追加 + 授权分页查询。
 */
public interface AuditLogRepository {

    void insert(AuditLog log);

    List<AuditLogRow> search(long tenantId, Long actorUserId, String actionCode, String targetType,
                             int offset, int size);

    long count(long tenantId, Long actorUserId, String actionCode, String targetType);

    /**
     * 列表投影：审计行字段 + 操作人登录名（扁平结构，避免嵌套 resultMap 行折叠）。
     */
    class AuditLogRow {

        private Long id;
        private Long tenantId;
        private Long actorUserId;
        private String actorUsername;
        private String actionCode;
        private String targetType;
        private String targetId;
        private String beforeJson;
        private String afterJson;
        private String traceId;
        private String clientIp;
        private String reason;
        private java.time.Instant createdAt;

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

        public Long getActorUserId() {
            return actorUserId;
        }

        public void setActorUserId(Long actorUserId) {
            this.actorUserId = actorUserId;
        }

        public String getActorUsername() {
            return actorUsername;
        }

        public void setActorUsername(String actorUsername) {
            this.actorUsername = actorUsername;
        }

        public String getActionCode() {
            return actionCode;
        }

        public void setActionCode(String actionCode) {
            this.actionCode = actionCode;
        }

        public String getTargetType() {
            return targetType;
        }

        public void setTargetType(String targetType) {
            this.targetType = targetType;
        }

        public String getTargetId() {
            return targetId;
        }

        public void setTargetId(String targetId) {
            this.targetId = targetId;
        }

        public String getBeforeJson() {
            return beforeJson;
        }

        public void setBeforeJson(String beforeJson) {
            this.beforeJson = beforeJson;
        }

        public String getAfterJson() {
            return afterJson;
        }

        public void setAfterJson(String afterJson) {
            this.afterJson = afterJson;
        }

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        public String getClientIp() {
            return clientIp;
        }

        public void setClientIp(String clientIp) {
            this.clientIp = clientIp;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public java.time.Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(java.time.Instant createdAt) {
            this.createdAt = createdAt;
        }
    }
}
