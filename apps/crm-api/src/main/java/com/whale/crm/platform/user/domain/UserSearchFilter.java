package com.whale.crm.platform.user.domain;

public record UserSearchFilter(
        String keyword,
        String status,
        String userType,
        Long teamId,
        String roleCode) {
}
