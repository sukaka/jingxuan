package com.whale.crm.integration.wecom.sidebar.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 企微侧边栏未关联外部联系人绑定 CRM 客户请求。
 */
public record WecomSidebarLinkContactRequest(
        @NotBlank @Size(max = 192) String externalUserid,
        @NotNull @Min(1) Long customerId,
        @Size(max = 1000) String reason) {
}
