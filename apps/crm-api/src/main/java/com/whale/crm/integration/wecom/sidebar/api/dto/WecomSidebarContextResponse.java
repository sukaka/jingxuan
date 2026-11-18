package com.whale.crm.integration.wecom.sidebar.api.dto;

import com.whale.crm.customer.api.dto.CustomerDetailResponse;

import java.time.Instant;

public record WecomSidebarContextResponse(String contextStatus, String corpId, String externalUserid,
                                          Employee employee, CustomerDetailResponse customer, Summary summary) {
    public record Employee(long userId, String displayName) {
    }

    public record Summary(Long totalOrders, Long remainingPackages, Long remainingCount, Instant lastFollowupAt) {
    }
}
