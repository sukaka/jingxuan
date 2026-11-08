package com.whale.crm.customer.domain;

import java.time.Instant;

/**
 * 原 23 字段扩展保留（crm_customer_legacy，只读视图）。历史导入落地前本表为空；
 * legacy_full_json 为原 23 字段原值 JSON（键为原名或 field_key）。
 */
public class CustomerLegacy {

    private Long id;
    private Long tenantId;
    private Long customerId;
    private String legacyTable;
    private String legacyRowId;
    private Instant legacySubmittedAt;
    private Instant legacyFirstImportAt;
    private String legacyAccount;
    private String legacyWriter;
    private String legacyNewOld;
    private String legacyFullJson;

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

    public String getLegacyTable() {
        return legacyTable;
    }

    public void setLegacyTable(String legacyTable) {
        this.legacyTable = legacyTable;
    }

    public String getLegacyRowId() {
        return legacyRowId;
    }

    public void setLegacyRowId(String legacyRowId) {
        this.legacyRowId = legacyRowId;
    }

    public Instant getLegacySubmittedAt() {
        return legacySubmittedAt;
    }

    public void setLegacySubmittedAt(Instant legacySubmittedAt) {
        this.legacySubmittedAt = legacySubmittedAt;
    }

    public Instant getLegacyFirstImportAt() {
        return legacyFirstImportAt;
    }

    public void setLegacyFirstImportAt(Instant legacyFirstImportAt) {
        this.legacyFirstImportAt = legacyFirstImportAt;
    }

    public String getLegacyAccount() {
        return legacyAccount;
    }

    public void setLegacyAccount(String legacyAccount) {
        this.legacyAccount = legacyAccount;
    }

    public String getLegacyWriter() {
        return legacyWriter;
    }

    public void setLegacyWriter(String legacyWriter) {
        this.legacyWriter = legacyWriter;
    }

    public String getLegacyNewOld() {
        return legacyNewOld;
    }

    public void setLegacyNewOld(String legacyNewOld) {
        this.legacyNewOld = legacyNewOld;
    }

    public String getLegacyFullJson() {
        return legacyFullJson;
    }

    public void setLegacyFullJson(String legacyFullJson) {
        this.legacyFullJson = legacyFullJson;
    }
}
