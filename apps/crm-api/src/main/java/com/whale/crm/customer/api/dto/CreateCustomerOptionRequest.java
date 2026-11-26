package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增客户来源或客户渠道选项（仅 customer:source_channel:manage）。
 * optionCode 选填，留空由服务端自动生成不可变业务代码；optionLabel 为展示名称。
 */
public record CreateCustomerOptionRequest(
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "选项编码需为 1-64 位字母/数字/_-")
        String optionCode,
        @NotBlank(message = "选项名称不能为空")
        @Size(max = 64, message = "选项名称最长 64 字符")
        String optionLabel,
        @Min(value = 0, message = "排序号无效") @Max(value = 999999, message = "排序号无效")
        Integer sortNo) {
    public CreateCustomerOptionRequest {
        optionCode = optionCode == null || optionCode.isBlank() ? null : optionCode.strip();
        optionLabel = optionLabel == null ? null : optionLabel.strip();
    }
}
