package com.whale.crm.customer.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 客户列表项。手机号、身份证号、邮箱、微信号均仅以脱敏形态返回；客户唯一标识为 id/customerNo。
 */
public record CustomerListItemResponse(
        Long id,
        String customerNo,
        String displayName,
        String nickname,
        String mobileMasked,
        String idCardMasked,
        String emailMasked,
        String wechatIdMasked,
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
        List<String> tagCodes,
        Instant nextFollowupAt,
        Integer addedStewardCount,
        List<String> addedStewardNames) {
    public CustomerListItemResponse {
        tagCodes = tagCodes == null ? null : List.copyOf(tagCodes);
        addedStewardNames = addedStewardNames == null ? null : List.copyOf(addedStewardNames);
    }

    public CustomerListItemResponse(
            Long id, String customerNo, String displayName, String nickname, String mobileMasked,
            String cityCode, String customerStatus, String tierCode, String intentLevel,
            Long ownerStaffId, String ownerName, Long teamId, Integer pointsCached,
            Instant lastFollowupAt, Instant createdAt, Long version, String sourceCode,
            String channelCode, String spendingLevel, LocalDate birthday,
            List<String> tagCodes, Instant nextFollowupAt, Integer addedStewardCount,
            List<String> addedStewardNames) {
        this(id, customerNo, displayName, nickname, mobileMasked, null, null, null,
                cityCode, customerStatus, tierCode, intentLevel, ownerStaffId, ownerName, teamId,
                pointsCached, lastFollowupAt, createdAt, version, sourceCode, channelCode, spendingLevel,
                birthday, tagCodes, nextFollowupAt, addedStewardCount, addedStewardNames);
    }

    public CustomerListItemResponse(
            Long id, String customerNo, String displayName, String nickname, String mobileMasked,
            String cityCode, String customerStatus, String tierCode, String intentLevel,
            Long ownerStaffId, String ownerName, Long teamId, Integer pointsCached,
            Instant lastFollowupAt, Instant createdAt, Long version, String sourceCode,
            String channelCode, String spendingLevel, LocalDate birthday,
            List<String> tagCodes, Instant nextFollowupAt) {
        this(id, customerNo, displayName, nickname, mobileMasked, null, null, null,
                cityCode, customerStatus, tierCode, intentLevel, ownerStaffId, ownerName, teamId,
                pointsCached, lastFollowupAt, createdAt, version, sourceCode, channelCode, spendingLevel,
                birthday, tagCodes, nextFollowupAt, null, null);
    }
}
