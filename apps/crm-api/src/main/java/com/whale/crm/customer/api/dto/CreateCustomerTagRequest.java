package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增客户标签内容（仅 customer:tag:manage）。
 * optionCode 选填，留空由服务端自动生成不可变代码；optionLabel 为标签展示名称。
 */
public record CreateCustomerTagRequest(
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "标签编码需为 1-64 位字母/数字/_-")
        String optionCode,
        @NotBlank(message = "标签内容不能为空")
        @Size(max = 64, message = "标签内容最长 64 字符")
        String optionLabel,
        @Min(value = 0, message = "排序号无效") @Max(value = 999999, message = "排序号无效")
        Integer sortNo) {
    public CreateCustomerTagRequest {
        optionCode = optionCode == null || optionCode.isBlank() ? null : optionCode.strip();
        optionLabel = optionLabel == null ? null : optionLabel.strip();
    }
}
