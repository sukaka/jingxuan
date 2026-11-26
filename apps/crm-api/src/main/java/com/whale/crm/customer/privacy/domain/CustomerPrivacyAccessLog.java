package com.whale.crm.customer.privacy.domain;

import java.time.Instant;

/**
 * 客户隐私信息揭盲审计日志（crm_customer_privacy_access_log，只追加不可覆盖）。
 */
public class CustomerPrivacyAccessLog {

    public static final String ACCESS_MODE_ADMIN_DIRECT = "ADMIN_DIRECT_REVEAL";
    public static final String ACCESS_MODE_APPROVED_GRANT = "APPROVED_GRANT_REVEAL";

    private Long id;
    private Long tenantId;
    private Long customerId;
    private Long actorUserId;
    private String actorRoleSnapshot;
    private String accessMode;
    private Long requestId;
    private String revealedFieldsJson;
    private String maskedValuesSnapshotJson;
    private String reason;
    private String clientIp;
    private String userAgent;
    private String traceId;
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

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(Long actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getActorRoleSnapshot() {
        return actorRoleSnapshot;
    }

    public void setActorRoleSnapshot(String actorRoleSnapshot) {
        this.actorRoleSnapshot = actorRoleSnapshot;
    }

    public String getAccessMode() {
        return accessMode;
    }

    public void setAccessMode(String accessMode) {
        this.accessMode = accessMode;
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public String getRevealedFieldsJson() {
        return revealedFieldsJson;
    }

    public void setRevealedFieldsJson(String revealedFieldsJson) {
        this.revealedFieldsJson = revealedFieldsJson;
    }

    public String getMaskedValuesSnapshotJson() {
        return maskedValuesSnapshotJson;
    }

    public void setMaskedValuesSnapshotJson(String maskedValuesSnapshotJson) {
        this.maskedValuesSnapshotJson = maskedValuesSnapshotJson;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
