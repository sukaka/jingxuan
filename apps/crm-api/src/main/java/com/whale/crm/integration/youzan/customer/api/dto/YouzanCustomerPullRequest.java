package com.whale.crm.integration.youzan.customer.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 触发有赞客户拉取与 CRM 正式建档请求。
 */
public record YouzanCustomerPullRequest(
        @NotBlank(message = "店铺编号不能为空")
        @Size(max = 64, message = "店铺编号不能超过64字符")
        String shopId,
        @NotBlank(message = "数据源模式不能为空")
        @Size(max = 32, message = "数据源模式不能超过32字符")
        String sourceMode,
        @Size(max = 16, message = "拉取模式不能超过16字符")
        String pullMode,
        @Min(value = 1, message = "单次拉取条数不能小于1")
        @Max(value = 500, message = "单次拉取条数不能超过500")
        Integer limit,
        @NotNull(message = "目标专属管家不能为空")
        @Min(value = 1, message = "目标专属管家ID无效")
        Long ownerStaffId,
        @Size(max = 64, message = "客户来源编码不能超过64字符")
        String sourceCode,
        @Size(max = 64, message = "客户渠道编码不能超过64字符")
        String channelCode,
        @Size(max = 32, message = "开始日期格式无效")
        String startDate,
        @Size(max = 32, message = "结束日期格式无效")
        String endDate,
        Boolean enrichProfile,
        Boolean resetCursor
) {
}
