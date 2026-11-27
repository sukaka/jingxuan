package com.whale.crm.integration.youzan.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record YouzanOrderAssignRequest(
        @NotNull(message = "目标归属管家不能为空")
        @Positive(message = "目标归属管家ID必须为正整数")
        Long stewardUserId,
        @Size(max = 500, message = "分配理由不能超过500字")
        String reason,
        Long version,
        Boolean saveAlias,
        @Size(max = 64, message = "账号别名长度不能超过64")
        String aliasName) {
}
