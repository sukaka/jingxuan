package com.whale.crm.customer.domain;

import java.time.Instant;

/**
 * 会员积分账本流水（crm_points_ledger）。分数与来源事实只追加；客户余额 = SUM(delta)，
 * crm_customer.points_cached 只是与流水同事务重算的缓存，不允许独立修改余额。
 * 人工确认合并可保留原流水并改挂主客户，不能篡改 delta。
 */
public class PointsLedgerEntry {

    private Long id;
    private Long tenantId;
    private Long customerId;
    private Integer delta;
    private String reasonCode;
    private String sourceType;
    private String sourceRef;
    private Long operatorUserId;
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

    public Integer getDelta() {
        return delta;
    }

    public void setDelta(Integer delta) {
        this.delta = delta;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
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

    public Long getOperatorUserId() {
        return operatorUserId;
    }

    public void setOperatorUserId(Long operatorUserId) {
        this.operatorUserId = operatorUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
