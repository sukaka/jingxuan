package com.whale.crm.platform.user.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 用户列表/详情对外 DTO。绝不包含 passwordHash。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserSummaryResponse(
        Long id,
        String username,
        String displayName,
        String userType,
        String status,
        Long teamId,
        String teamName,
        List<String> roles,
        Instant lastLoginAt,
        Instant createdAt,
        Long version) {
    public UserSummaryResponse {
        roles = roles == null ? null : List.copyOf(roles);
    }
}
