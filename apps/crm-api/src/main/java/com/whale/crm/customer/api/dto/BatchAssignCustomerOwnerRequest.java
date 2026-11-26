package com.whale.crm.customer.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 主管/管理员批量分配客户归属（最多 100 位客户，按客户 ID 升序加锁同事务生效）。
 */
public record BatchAssignCustomerOwnerRequest(
        @NotEmpty(message = "请至少选择一位客户")
        @Size(max = 100, message = "单次批量分配最多支持 100 位客户")
        List<@Valid BatchAssignItem> items,
        @NotNull(message = "请选择目标管家")
        @Positive(message = "目标管家 ID 必须为正整数")
        Long newOwnerId,
        @NotBlank(message = "分配原因不能为空")
        @Size(max = 500, message = "分配原因不能超过 500 字")
        String reason) {

    public BatchAssignCustomerOwnerRequest {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record BatchAssignItem(
            @NotNull(message = "客户 ID 不能为空")
            @Positive(message = "客户 ID 必须为正整数")
            Long customerId,
            @NotNull(message = "缺少客户版本号")
            @Min(value = 0, message = "版本号不能为负数")
            Long version) {
    }
}
