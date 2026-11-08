package com.whale.crm.customer.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 客户详情 = 列表字段 + 档案字段 + 标签/身份。deleted=true 表示已被合并归档（只读）。
 */
public record CustomerDetailResponse(
        Long id,
        String customerNo,
        String displayName,
        String nickname,
        String mobileMasked,
        String cityCode,
        String customerStatus,
        String tierCode,
        String intentLevel,
        Long ownerStaffId,
        String ownerName,
        Long teamId,
        Integer pointsCached,
        Instant lastFollowupAt,
        Instant createdAt,
        Long version,
        String sourceCode,
        String channelCode,
        String spendingLevel,
        LocalDate birthday,
        String attentionProjects,
        String blockerNote,
        String remark,
        String remarkName,
        Instant firstAddedAt,
        Instant firstOrderAt,
        List<String> tagCodes,
        List<CustomerIdentityItemResponse> identities,
        boolean deleted) {
    public CustomerDetailResponse {
        tagCodes = tagCodes == null ? null : List.copyOf(tagCodes);
        identities = identities == null ? null : List.copyOf(identities);
    }
}
