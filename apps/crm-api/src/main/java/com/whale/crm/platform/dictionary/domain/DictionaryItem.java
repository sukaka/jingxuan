package com.whale.crm.platform.dictionary.domain;

import java.time.Instant;

/**
 * 字典选项实体（sys_dictionary_item）。生命周期 DRAFT → ACTIVE → INACTIVE → ARCHIVED。
 * option_code 为不可变业务代码；停用不物理删除，历史单据仍可引用。
 */
public class DictionaryItem {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    private Long id;
    private Long tenantId;
    private Long dictTypeId;
    private String optionCode;
    private String optionLabel;
    private Integer sortNo;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    /**
     * 仅 ACTIVE 可停用；重复停用/已归档视为状态冲突。
     */
    public void disable() {
        if (!STATUS_ACTIVE.equals(status)) {
            throw new IllegalStateException("仅 ACTIVE 状态的字典项可停用");
        }
        this.status = STATUS_INACTIVE;
    }

    public boolean isEditable() {
        return !STATUS_ARCHIVED.equals(status);
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

    public Long getDictTypeId() {
        return dictTypeId;
    }

    public void setDictTypeId(Long dictTypeId) {
        this.dictTypeId = dictTypeId;
    }

    public String getOptionCode() {
        return optionCode;
    }

    public void setOptionCode(String optionCode) {
        this.optionCode = optionCode;
    }

    public String getOptionLabel() {
        return optionLabel;
    }

    public void setOptionLabel(String optionLabel) {
        this.optionLabel = optionLabel;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
