package com.whale.crm.customer.privacy.domain;

import java.time.Instant;

/**
 * 客户隐私查看申请单（crm_customer_privacy_request）。
 * 状态机：PENDING -> APPROVED / REJECTED / CANCELED；APPROVED -> REVOKED（或到期自动失效）。
 */
public class CustomerPrivacyRequest {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELED = "CANCELED";
    public static final String STATUS_REVOKED = "REVOKED";

    private Long id;
    private Long tenantId;
    private String requestNo;
    private Long customerId;
    private Long applicantUserId;
    private Long applicantTeamId;
    private String requestedFieldsJson;
    private String approvedFieldsJson;
    private String reason;
    private String status;
    private Long assignedApproverId;
    private String approverSource;
    private Integer requestedDurationMinutes;
    private Integer approvedDurationMinutes;
    private Long reviewedBy;
    private Instant reviewedAt;
    private String reviewReason;
    private Instant grantStartsAt;
    private Instant grantExpiresAt;
    private Long revokedBy;
    private Instant revokedAt;
    private String revokeReason;
    private Integer revealCount;
    private Instant lastRevealedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    public boolean isApproved() {
        return STATUS_APPROVED.equals(status);
    }

    public boolean isActiveGrantAt(Instant now) {
        return isApproved()
                && grantStartsAt != null
                && grantExpiresAt != null
                && !now.isBefore(grantStartsAt)
                && now.isBefore(grantExpiresAt);
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

    public String getRequestNo() {
        return requestNo;
    }

    public void setRequestNo(String requestNo) {
        this.requestNo = requestNo;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getApplicantUserId() {
        return applicantUserId;
    }

    public void setApplicantUserId(Long applicantUserId) {
        this.applicantUserId = applicantUserId;
    }

    public Long getApplicantTeamId() {
        return applicantTeamId;
    }

    public void setApplicantTeamId(Long applicantTeamId) {
        this.applicantTeamId = applicantTeamId;
    }

    public String getRequestedFieldsJson() {
        return requestedFieldsJson;
    }

    public void setRequestedFieldsJson(String requestedFieldsJson) {
        this.requestedFieldsJson = requestedFieldsJson;
    }

    public String getApprovedFieldsJson() {
        return approvedFieldsJson;
    }

    public void setApprovedFieldsJson(String approvedFieldsJson) {
        this.approvedFieldsJson = approvedFieldsJson;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getAssignedApproverId() {
        return assignedApproverId;
    }

    public void setAssignedApproverId(Long assignedApproverId) {
        this.assignedApproverId = assignedApproverId;
    }

    public String getApproverSource() {
        return approverSource;
    }

    public void setApproverSource(String approverSource) {
        this.approverSource = approverSource;
    }

    public Integer getRequestedDurationMinutes() {
        return requestedDurationMinutes;
    }

    public void setRequestedDurationMinutes(Integer requestedDurationMinutes) {
        this.requestedDurationMinutes = requestedDurationMinutes;
    }

    public Integer getApprovedDurationMinutes() {
        return approvedDurationMinutes;
    }

    public void setApprovedDurationMinutes(Integer approvedDurationMinutes) {
        this.approvedDurationMinutes = approvedDurationMinutes;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }

    public Instant getGrantStartsAt() {
        return grantStartsAt;
    }

    public void setGrantStartsAt(Instant grantStartsAt) {
        this.grantStartsAt = grantStartsAt;
    }

    public Instant getGrantExpiresAt() {
        return grantExpiresAt;
    }

    public void setGrantExpiresAt(Instant grantExpiresAt) {
        this.grantExpiresAt = grantExpiresAt;
    }

    public Long getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(Long revokedBy) {
        this.revokedBy = revokedBy;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public void setRevokeReason(String revokeReason) {
        this.revokeReason = revokeReason;
    }

    public Integer getRevealCount() {
        return revealCount;
    }

    public void setRevealCount(Integer revealCount) {
        this.revealCount = revealCount;
    }

    public Instant getLastRevealedAt() {
        return lastRevealedAt;
    }

    public void setLastRevealedAt(Instant lastRevealedAt) {
        this.lastRevealedAt = lastRevealedAt;
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
