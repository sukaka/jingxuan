package com.whale.crm.customer.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 客户详情 = 列表字段 + 档案字段 + 标签/身份。deleted=true 表示已被合并归档（只读）。
 * attentionProjects 保留旧文本，attentionProjectIds 才是可用于筛选的正规关注关系。
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
        boolean deleted,
        List<Long> attentionProjectIds,
        Integer addedStewardCount,
        List<CustomerStewardRelationItemResponse> addedStewards) {
    public CustomerDetailResponse {
        tagCodes = tagCodes == null ? null : List.copyOf(tagCodes);
        identities = identities == null ? null : List.copyOf(identities);
        attentionProjectIds = attentionProjectIds == null ? List.of() : List.copyOf(attentionProjectIds);
        addedStewards = addedStewards == null ? null : List.copyOf(addedStewards);
    }

    public CustomerDetailResponse(
            Long id, String customerNo, String displayName, String nickname, String mobileMasked,
            String cityCode, String customerStatus, String tierCode, String intentLevel,
            Long ownerStaffId, String ownerName, Long teamId, Integer pointsCached,
            Instant lastFollowupAt, Instant createdAt, Long version, String sourceCode,
            String channelCode, String spendingLevel, LocalDate birthday,
            String attentionProjects, String blockerNote, String remark, String remarkName,
            Instant firstAddedAt, Instant firstOrderAt, List<String> tagCodes,
            List<CustomerIdentityItemResponse> identities, boolean deleted,
            List<Long> attentionProjectIds) {
        this(id, customerNo, displayName, nickname, mobileMasked, cityCode, customerStatus, tierCode,
                intentLevel, ownerStaffId, ownerName, teamId, pointsCached, lastFollowupAt, createdAt,
                version, sourceCode, channelCode, spendingLevel, birthday, attentionProjects, blockerNote,
                remark, remarkName, firstAddedAt, firstOrderAt, tagCodes, identities, deleted,
                attentionProjectIds, null, null);
    }
}
