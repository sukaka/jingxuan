package com.whale.crm.integration.youzan.customer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重置有赞客户拉取断点游标请求。
 */
public record YouzanCustomerCursorResetRequest(
        @NotBlank(message = "店铺编号不能为空")
        @Size(max = 64, message = "店铺编号不能超过64字符")
        String shopId,
        @NotBlank(message = "数据源模式不能为空")
        @Size(max = 32, message = "数据源模式不能超过32字符")
        String sourceMode
) {
}
