package com.whale.crm.reporting.api.dto;

import com.whale.crm.reporting.domain.ReportCriteria;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public class ReportCriteriaRequest {
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;
    @Size(max = 64)
    private String timeZone = "UTC";
    @Pattern(regexp = "[A-Za-z0-9_]{1,48}")
    private String sourceSystem = "ALL";
    @Size(max = 32)
    private String receiverType = "ALL";
    @Size(max = 32)
    private String metricVersion = "P2_FACT_V1";
    @Positive
    private Long customerId;
    @Positive
    private Long campaignId;
    @Positive
    private Long campaignBatchId;
    @Positive
    private Long channelId;
    @Positive
    private Long staffUserId;
    @Positive
    private Long skuId;
    @Positive
    private Long institutionId;

    public ReportCriteria toCriteria() {
        return new ReportCriteria(from, to, timeZone, sourceSystem, receiverType, metricVersion,
                customerId, campaignId, campaignBatchId, channelId, staffUserId, skuId, institutionId);
    }

    public LocalDate getFrom() { return from; }
    public void setFrom(LocalDate value) { from = value; }
    public LocalDate getTo() { return to; }
    public void setTo(LocalDate value) { to = value; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String value) { timeZone = value; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String value) { sourceSystem = value; }
    public String getReceiverType() { return receiverType; }
    public void setReceiverType(String value) { receiverType = value; }
    public String getMetricVersion() { return metricVersion; }
    public void setMetricVersion(String value) { metricVersion = value; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long value) { customerId = value; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long value) { campaignId = value; }
    public Long getCampaignBatchId() { return campaignBatchId; }
    public void setCampaignBatchId(Long value) { campaignBatchId = value; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long value) { channelId = value; }
    public Long getStaffUserId() { return staffUserId; }
    public void setStaffUserId(Long value) { staffUserId = value; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long value) { skuId = value; }
    public Long getInstitutionId() { return institutionId; }
    public void setInstitutionId(Long value) { institutionId = value; }
}
