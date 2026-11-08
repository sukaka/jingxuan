package com.whale.crm.customer.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 客户列表查询投影：crm_customer 联表 sys_user 取 ownerName。mobileCipher 永不进入投影，
 * 列表/详情的手机号一律由应用层解密后脱敏输出。
 */
public class CustomerListRow {

    private Long id;
    private Long tenantId;
    private Long ownerStaffId;
    private String ownerName;
    private Long teamId;
    private String customerNo;
    private String displayName;
    private String nickname;
    private String mobileCipher;
    private String cityCode;
    private String customerStatus;
    private String tierCode;
    private String intentLevel;
    private String sourceCode;
    private String channelCode;
    private String spendingLevel;
    private LocalDate birthday;
    private String tagCodes;
    private Instant nextFollowupAt;
    private Integer pointsCached;
    private Instant lastFollowupAt;
    private Instant createdAt;
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

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
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

    public String getMobileCipher() {
        return mobileCipher;
    }

    public void setMobileCipher(String mobileCipher) {
        this.mobileCipher = mobileCipher;
    }

    public String getCityCode() {
        return cityCode;
    }

    public void setCityCode(String cityCode) {
        this.cityCode = cityCode;
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

    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String value) { sourceCode = value; }
    public String getChannelCode() { return channelCode; }
    public void setChannelCode(String value) { channelCode = value; }
    public String getSpendingLevel() { return spendingLevel; }
    public void setSpendingLevel(String value) { spendingLevel = value; }
    public LocalDate getBirthday() { return birthday; }
    public void setBirthday(LocalDate value) { birthday = value; }
    public String getTagCodes() { return tagCodes; }
    public void setTagCodes(String value) { tagCodes = value; }
    public Instant getNextFollowupAt() { return nextFollowupAt; }
    public void setNextFollowupAt(Instant value) { nextFollowupAt = value; }

    public Integer getPointsCached() {
        return pointsCached;
    }

    public void setPointsCached(Integer pointsCached) {
        this.pointsCached = pointsCached;
    }

    public Instant getLastFollowupAt() {
        return lastFollowupAt;
    }

    public void setLastFollowupAt(Instant lastFollowupAt) {
        this.lastFollowupAt = lastFollowupAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
