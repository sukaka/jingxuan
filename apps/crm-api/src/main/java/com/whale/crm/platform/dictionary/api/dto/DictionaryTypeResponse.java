package com.whale.crm.platform.dictionary.api.dto;

import java.util.List;

/**
 * 字典类型管理端 DTO（含全部状态选项）。
 */
public record DictionaryTypeResponse(
        Long id,
        String dictCode,
        String dictName,
        String status,
        Long version,
        List<DictionaryItemResponse> items) {
    public DictionaryTypeResponse {
        items = items == null ? null : List.copyOf(items);
    }
}
