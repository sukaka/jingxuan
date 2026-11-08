package com.whale.crm.platform.user.domain;

import java.time.Instant;

/**
 * 数据范围授权行（sys_data_scope）。scope_type=SELF/TEAM/ALL/INSTITUTION；
 * TEAM 时 scope_ref_id 为 team_id，ALL/SELF 时为空。
 */
public class DataScopeRow {

    private Long id;
    private Long tenantId;
    private Long userId;
    private String resource;
    private String scopeType;
    private Long scopeRefId;
    private Instant expiresAt;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public Long getScopeRefId() {
        return scopeRefId;
    }

    public void setScopeRefId(Long scopeRefId) {
        this.scopeRefId = scopeRefId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
