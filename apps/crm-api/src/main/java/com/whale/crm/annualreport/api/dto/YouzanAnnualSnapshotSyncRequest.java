package com.whale.crm.annualreport.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 拉取有赞历史订单只读快照与买家名称/微信身份请求。
 */
public record YouzanAnnualSnapshotSyncRequest(
        @NotBlank @Size(max = 64) String shopId,
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$") String startDate,
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$") String endDate,
        Boolean enrichBuyerProfile,
        @Min(1) @Max(100) Integer maxPagesPerWindow
) {
}
