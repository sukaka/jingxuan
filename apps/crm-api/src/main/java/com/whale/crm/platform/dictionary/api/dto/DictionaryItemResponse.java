package com.whale.crm.platform.dictionary.api.dto;

import java.time.Instant;

/**
 * 字典项管理端 DTO（含状态与版本，含停用历史项）。
 */
public record DictionaryItemResponse(
        Long id,
        String optionCode,
        String optionLabel,
        Integer sortNo,
        String status,
        Long version,
        Instant createdAt,
        Instant updatedAt) {
}
