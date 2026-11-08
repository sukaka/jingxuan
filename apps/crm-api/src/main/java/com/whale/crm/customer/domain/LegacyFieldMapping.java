package com.whale.crm.customer.domain;

/**
 * 原 23 字段映射字典（crm_legacy_field_mapping）。原名/原表/新字段/来源公式的权威登记，
 * 供 legacy-profile 接口逐项输出。
 */
public class LegacyFieldMapping {

    private Long id;
    private Long tenantId;
    private String fieldKey;
    private String originalName;
    private String originalTable;
    private String targetField;
    private String sourceFormula;
    private Integer sortOrder;
    private boolean enabled;

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

    public String getFieldKey() {
        return fieldKey;
    }

    public void setFieldKey(String fieldKey) {
        this.fieldKey = fieldKey;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getOriginalTable() {
        return originalTable;
    }

    public void setOriginalTable(String originalTable) {
        this.originalTable = originalTable;
    }

    public String getTargetField() {
        return targetField;
    }

    public void setTargetField(String targetField) {
        this.targetField = targetField;
    }

    public String getSourceFormula() {
        return sourceFormula;
    }

    public void setSourceFormula(String sourceFormula) {
        this.sourceFormula = sourceFormula;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
