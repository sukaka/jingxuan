package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 编辑客户（PATCH 白名单）。未传的字段保持不变（null 语义 = 不修改）；
 * mobile/idCard/email/wechatId 传空字符串表示清除对应隐私字段；拒绝包含脱敏占位符 '*'。
 * version 乐观锁，失配 409。
 */
public record UpdateCustomerRequest(
        @Size(max = 96) String displayName,
        @Size(max = 128) String nickname,
        @Size(max = 128) String remarkName,
        @Size(max = 32) String mobile,
        @Size(max = 64) String cityCode,
        @Size(max = 64) String sourceCode,
        @Size(max = 64) String channelCode,
        @Size(max = 64) String customerStatus,
        @Size(max = 16) String tierCode,
        @Size(max = 16) String intentLevel,
        @Size(max = 16) String spendingLevel,
        LocalDate birthday,
        @Size(max = 512) String attentionProjects,
        @Size(max = 2000) String blockerNote,
        @Size(max = 2000) String remark,
        @NotNull(message = "数据版本号不能为空") Long version,
        @Size(max = 100) List<Long> projectIds,
        @Size(max = 50, message = "标签数量超限") List<@Size(max = 64) String> tagCodes,
        @Size(max = 32, message = "身份证号最长 32 字符") String idCard,
        @Size(max = 128, message = "邮箱最长 128 字符") String email,
        @Size(max = 64, message = "微信号最长 64 字符") String wechatId) {
    public UpdateCustomerRequest {
        projectIds = projectIds == null ? null : List.copyOf(projectIds);
        tagCodes = tagCodes == null ? null : List.copyOf(tagCodes);
    }

    public UpdateCustomerRequest(String displayName, String nickname, String remarkName, String mobile,
                                 String cityCode, String sourceCode, String channelCode, String customerStatus,
                                 String tierCode, String intentLevel, String spendingLevel, LocalDate birthday,
                                 String attentionProjects, String blockerNote, String remark, Long version,
                                 List<Long> projectIds, List<String> tagCodes) {
        this(displayName, nickname, remarkName, mobile, cityCode, sourceCode, channelCode, customerStatus,
                tierCode, intentLevel, spendingLevel, birthday, attentionProjects, blockerNote, remark, version,
                projectIds, tagCodes, null, null, null);
    }
}
