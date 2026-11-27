package com.whale.crm.integration.youzan.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record YouzanStewardAliasRequest(
        @NotBlank(message = "有赞备注账号名不能为空")
        @Size(max = 64, message = "账号别名长度不能超过64")
        String aliasName,
        @NotNull(message = "目标管家不能为空")
        @Positive(message = "目标管家ID必须为正整数")
        Long stewardUserId,
        @Size(max = 16, message = "状态长度不能超过16")
        String status,
        @Size(max = 255, message = "备注长度不能超过255")
        String remark,
        Long version,
        Boolean backfillPending) {
}
