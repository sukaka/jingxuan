package com.whale.crm.platform.dictionary.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修改字典项展示字段（label/sortNo）。optionCode 不可变，不在本 DTO 中；version 乐观锁必填。
 */
public record UpdateDictionaryItemRequest(
        @NotBlank(message = "选项名称不能为空") @Size(max = 128, message = "选项名称最长 128 字符")
        String optionLabel,

        @NotNull(message = "排序号必填") @Min(value = 0, message = "排序号无效") @Max(value = 999999, message = "排序号无效")
        Integer sortNo,

        @NotNull(message = "version 必填") @Min(value = 0, message = "version 无效") Long version) {
}
