package com.whale.crm.customer.domain;

import java.time.Instant;

/**
 * 疑似重复客户合并审批（crm_merge_case）。
 * 状态机 SUGGESTED → REVIEWING → CONFIRMED / REJECTED（docs/11 第 7 节）；
 * 未经 CONFIRMED 不得迁移任何关联数据；CONFIRMED 只能经独立更正审批转为 REVERSED。
 */
public class MergeCase {

    public static final String STATUS_SUGGESTED = "SUGGESTED";
    public static final String STATUS_REVIEWING = "REVIEWING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REVERSED = "REVERSED";

    private Long id;
    private Long tenantId;
    private Long primaryCustomerId;
    private Long secondaryCustomerId;
    private String status;
    private String matchEvidence;
    private Long reviewerId;
    private Instant reviewedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    /**
     * 普通审核流转仅 SUGGESTED/REVIEWING 开放；已确认单的更正由独立审批服务处理。
     */
    public boolean isOpen() {
        return STATUS_SUGGESTED.equals(status) || STATUS_REVIEWING.equals(status);
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

    public Long getPrimaryCustomerId() {
        return primaryCustomerId;
    }

    public void setPrimaryCustomerId(Long primaryCustomerId) {
        this.primaryCustomerId = primaryCustomerId;
    }

    public Long getSecondaryCustomerId() {
        return secondaryCustomerId;
    }

    public void setSecondaryCustomerId(Long secondaryCustomerId) {
        this.secondaryCustomerId = secondaryCustomerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMatchEvidence() {
        return matchEvidence;
    }

    public void setMatchEvidence(String matchEvidence) {
        this.matchEvidence = matchEvidence;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(Long reviewerId) {
        this.reviewerId = reviewerId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
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
