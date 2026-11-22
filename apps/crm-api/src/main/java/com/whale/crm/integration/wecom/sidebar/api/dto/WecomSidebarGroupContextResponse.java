package com.whale.crm.integration.wecom.sidebar.api.dto;

import java.util.List;

/**
 * 企微群聊侧边栏上下文响应：包含群摘要与当前员工数据范围内可见的已关联 CRM 客户列表。
 */
public record WecomSidebarGroupContextResponse(
        String contextStatus,
        String corpId,
        String chatId,
        WecomSidebarContextResponse.Employee employee,
        RoomSummary room,
        List<LinkedCustomer> customers) {

    public WecomSidebarGroupContextResponse {
        customers = customers == null ? List.of() : List.copyOf(customers);
    }

    public record RoomSummary(
            String roomId,
            String displayName,
            String status,
            int staffCount,
            int externalCount,
            int linkedCustomerCount) {
    }

    public record LinkedCustomer(
            long customerId,
            String customerNo,
            String displayName,
            String nickname,
            String externalUserid,
            String tierCode,
            String ownerName,
            String mobileMasked) {
    }
}
