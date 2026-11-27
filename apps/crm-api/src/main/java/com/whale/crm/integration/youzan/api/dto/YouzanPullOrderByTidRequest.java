package com.whale.crm.integration.youzan.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record YouzanPullOrderByTidRequest(
        @NotBlank(message = "店铺编号不能为空")
        @Size(max = 64, message = "店铺编号长度不能超过64")
        String shopId,
        @NotBlank(message = "有赞订单号不能为空")
        @Size(max = 128, message = "有赞订单号长度不能超过128")
        String tid) {
}
