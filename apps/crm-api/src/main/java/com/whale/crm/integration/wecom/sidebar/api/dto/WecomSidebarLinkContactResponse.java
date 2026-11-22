package com.whale.crm.integration.wecom.sidebar.api.dto;

/**
 * 企微侧边栏外部联系人绑定 CRM 客户结果。
 */
public record WecomSidebarLinkContactResponse(
        long contactId,
        String corpId,
        String externalUserid,
        long staffUserId,
        long customerId,
        String linkType,
        String linkReason,
        boolean alreadyLinked) {
}
