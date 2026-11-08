package com.whale.crm.customer.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 客户主档案（crm_customer）。客户唯一标识为 id/customer_no，不等于手机号或昵称。
 * 手机号仅存 mobile_cipher（AES-256-GCM）+ mobile_hash（HMAC 盲索引），任何层不得出现明文列。
 * deleted=1 为软删除（合并后置）；points_cached 只是积分账本缓存，禁止直接 SET 余额。
 */
public class Customer {

    private Long id;
    private Long tenantId;
    private Long ownerStaffId;
    private Long teamId;
    private String customerNo;
    private String displayName;
    private String nickname;
    private String remarkName;
    private String mobileCipher;
    private String mobileHash;
    private String cityCode;
    private String sourceCode;
    private String channelCode;
    private String customerStatus;
    private String tierCode;
    private String intentLevel;
    private String spendingLevel;
    private LocalDate birthday;
    private Integer pointsCached;
    private String attentionProjects;
    private String blockerNote;
    private String remark;
    private Instant firstAddedAt;
    private Instant firstOrderAt;
    private Instant lastFollowupAt;
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

    public Long getOwnerStaffId() {
        return ownerStaffId;
    }

    public void setOwnerStaffId(Long ownerStaffId) {
        this.ownerStaffId = ownerStaffId;
    }

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }

    public String getCustomerNo() {
        return customerNo;
    }

    public void setCustomerNo(String customerNo) {
        this.customerNo = customerNo;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRemarkName() {
        return remarkName;
    }

    public void setRemarkName(String remarkName) {
        this.remarkName = remarkName;
    }

    public String getMobileCipher() {
        return mobileCipher;
    }

    public void setMobileCipher(String mobileCipher) {
        this.mobileCipher = mobileCipher;
    }

    public String getMobileHash() {
        return mobileHash;
    }

    public void setMobileHash(String mobileHash) {
        this.mobileHash = mobileHash;
    }

    public String getCityCode() {
        return cityCode;
    }

    public void setCityCode(String cityCode) {
        this.cityCode = cityCode;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public String getCustomerStatus() {
        return customerStatus;
    }

    public void setCustomerStatus(String customerStatus) {
        this.customerStatus = customerStatus;
    }

    public String getTierCode() {
        return tierCode;
    }

    public void setTierCode(String tierCode) {
        this.tierCode = tierCode;
    }

    public String getIntentLevel() {
        return intentLevel;
    }

    public void setIntentLevel(String intentLevel) {
        this.intentLevel = intentLevel;
    }

    public String getSpendingLevel() {
        return spendingLevel;
    }

    public void setSpendingLevel(String spendingLevel) {
        this.spendingLevel = spendingLevel;
    }

    public LocalDate getBirthday() {
        return birthday;
    }

    public void setBirthday(LocalDate birthday) {
        this.birthday = birthday;
    }

    public Integer getPointsCached() {
        return pointsCached;
    }

    public void setPointsCached(Integer pointsCached) {
        this.pointsCached = pointsCached;
    }

    public String getAttentionProjects() {
        return attentionProjects;
    }

    public void setAttentionProjects(String attentionProjects) {
        this.attentionProjects = attentionProjects;
    }

    public String getBlockerNote() {
        return blockerNote;
    }

    public void setBlockerNote(String blockerNote) {
        this.blockerNote = blockerNote;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Instant getFirstAddedAt() {
        return firstAddedAt;
    }

    public void setFirstAddedAt(Instant firstAddedAt) {
        this.firstAddedAt = firstAddedAt;
    }

    public Instant getFirstOrderAt() {
        return firstOrderAt;
    }

    public void setFirstOrderAt(Instant firstOrderAt) {
        this.firstOrderAt = firstOrderAt;
    }

    public Instant getLastFollowupAt() {
        return lastFollowupAt;
    }

    public void setLastFollowupAt(Instant lastFollowupAt) {
        this.lastFollowupAt = lastFollowupAt;
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
