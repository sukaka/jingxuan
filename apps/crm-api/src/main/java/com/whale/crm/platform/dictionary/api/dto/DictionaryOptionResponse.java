package com.whale.crm.platform.dictionary.api.dto;

/**
 * 字典选项 code/label/sortNo。下拉 options 仅含 ACTIVE；单项校验返回值供业务创建时快照持久化。
 */
public record DictionaryOptionResponse(String optionCode, String optionLabel, Integer sortNo) {
}
