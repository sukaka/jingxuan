package com.whale.crm.platform.dictionary.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建字典项。dictCode 定位类型；optionCode 创建后不可变；初始状态 ACTIVE。
 */
public record CreateDictionaryItemRequest(
        @NotBlank(message = "字典编码不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "字典编码格式无效")
        String dictCode,

        @NotBlank(message = "选项代码不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{1,96}", message = "选项代码需为 1-96 位字母/数字/_-")
        String optionCode,

        @NotBlank(message = "选项名称不能为空") @Size(max = 128, message = "选项名称最长 128 字符")
        String optionLabel,

        @Min(value = 0, message = "排序号无效") @Max(value = 999999, message = "排序号无效")
        Integer sortNo) {
}
