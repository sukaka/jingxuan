package com.whale.crm.customer.api.dto;

/**
 * 原 23 字段逐项视图。availability: AVAILABLE（新系统真实值/已导入原值）/
 * PENDING_IMPORT（待历史导入 crm_customer_legacy）/ PENDING_DOMAIN（依赖未落地域，不伪造值）。
 */
public record LegacyProfileItemResponse(
        String fieldKey,
        String originalName,
        String originalTable,
        String targetField,
        String sourceFormula,
        String value,
        String availability) {
}
