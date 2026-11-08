package com.whale.crm.customer.domain;

import java.time.Instant;

/**
 * 每日沟通流水（crm_followup）。每次跟进追加新记录，不覆盖历史；删除仅允许软删 deleted=1。
 * estimated_amount_fen 为整数分，禁止 float/double。
 */
public class Followup {

    private Long id;
    private Long tenantId;
    private Long customerId;
    private Long ownerStaffId;
    private String channelCode;
    private Instant contactAt;
    private String summary;
    private String intentProject;
    private Long estimatedAmountFen;
    private Instant nextFollowupAt;
    private String blockerNote;
    private Long createdBy;
    private boolean deleted;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

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

    public Long getOwnerStaffId() {
        return ownerStaffId;
    }

    public void setOwnerStaffId(Long ownerStaffId) {
        this.ownerStaffId = ownerStaffId;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public Instant getContactAt() {
        return contactAt;
    }

    public void setContactAt(Instant contactAt) {
        this.contactAt = contactAt;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getIntentProject() {
        return intentProject;
    }

    public void setIntentProject(String intentProject) {
        this.intentProject = intentProject;
    }

    public Long getEstimatedAmountFen() {
        return estimatedAmountFen;
    }

    public void setEstimatedAmountFen(Long estimatedAmountFen) {
        this.estimatedAmountFen = estimatedAmountFen;
    }

    public Instant getNextFollowupAt() {
        return nextFollowupAt;
    }

    public void setNextFollowupAt(Instant nextFollowupAt) {
        this.nextFollowupAt = nextFollowupAt;
    }

    public String getBlockerNote() {
        return blockerNote;
    }

    public void setBlockerNote(String blockerNote) {
        this.blockerNote = blockerNote;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
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
