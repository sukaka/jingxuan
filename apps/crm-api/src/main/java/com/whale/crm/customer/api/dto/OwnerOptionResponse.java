package com.whale.crm.customer.api.dto;

/**
 * 可选管家/主管下拉项。
 */
public record OwnerOptionResponse(Long id, String displayName, Long teamId, String teamName) {
}
