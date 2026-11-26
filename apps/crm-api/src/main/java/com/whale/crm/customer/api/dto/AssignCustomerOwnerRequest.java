package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 主管/管理员直接分配客户归属（单客立即生效，同事务追加不可修改归属历史与审计）。
 */
public record AssignCustomerOwnerRequest(
        @NotNull(message = "请选择目标管家")
        @Positive(message = "目标管家 ID 必须为正整数")
        Long newOwnerId,
        @NotBlank(message = "分配原因不能为空")
        @Size(max = 500, message = "分配原因不能超过 500 字")
        String reason,
        @NotNull(message = "缺少客户版本号")
        @Min(value = 0, message = "版本号不能为负数")
        Long version) {
}
