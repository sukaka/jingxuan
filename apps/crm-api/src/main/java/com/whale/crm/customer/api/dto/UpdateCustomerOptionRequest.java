package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修改客户来源或客户渠道名称与排序（仅 customer:source_channel:manage）；optionCode 不可变，version 乐观锁必填。
 */
public record UpdateCustomerOptionRequest(
        @NotBlank(message = "选项名称不能为空")
        @Size(max = 64, message = "选项名称最长 64 字符")
        String optionLabel,
        @NotNull(message = "排序号必填") @Min(value = 0, message = "排序号无效") @Max(value = 999999, message = "排序号无效")
        Integer sortNo,
        @NotNull(message = "数据版本号必填") @Min(value = 0, message = "数据版本号无效")
        Long version) {
    public UpdateCustomerOptionRequest {
        optionLabel = optionLabel == null ? null : optionLabel.strip();
    }
}
