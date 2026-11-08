package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 全量替换客户标签；审计 before/after。
 */
public record UpdateCustomerTagsRequest(
        @NotNull(message = "tagCodes 不能为空")
        @Size(max = 50, message = "标签数量超限")
        List<@Size(max = 64) String> tagCodes) {
    public UpdateCustomerTagsRequest {
        tagCodes = tagCodes == null ? null : List.copyOf(tagCodes);
    }
}
