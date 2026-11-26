package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 新建客户。nickname/displayName 至少其一（Service 校验）；mobile/idCard/email/wechatId 可空；
 * 隐私字段落库仅存密文与盲索引，接口永不回显明文；customerNo 由服务端生成。
 */
public record CreateCustomerRequest(
        @Size(max = 96, message = "客户姓名最长 96 字符") String displayName,
        @Size(max = 128, message = "客户昵称最长 128 字符") String nickname,
        @Size(max = 32, message = "手机号最长 32 字符") String mobile,
        Long ownerStaffId,
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
        @Size(max = 128) String remarkName,
        Instant firstAddedAt,
        @Size(max = 100) List<Long> projectIds,
        @Size(max = 50, message = "标签数量超限") List<@Size(max = 64) String> tagCodes,
        @Size(max = 32, message = "身份证号最长 32 字符") String idCard,
        @Size(max = 128, message = "邮箱最长 128 字符") String email,
        @Size(max = 64, message = "微信号最长 64 字符") String wechatId) {
    public CreateCustomerRequest {
        projectIds = projectIds == null ? null : List.copyOf(projectIds);
        tagCodes = tagCodes == null ? null : List.copyOf(tagCodes);
    }

    public CreateCustomerRequest(String displayName, String nickname, String mobile, Long ownerStaffId,
                                 String cityCode, String sourceCode, String channelCode, String customerStatus,
                                 String tierCode, String intentLevel, String spendingLevel, LocalDate birthday,
                                 String attentionProjects, String blockerNote, String remark, String remarkName,
                                 Instant firstAddedAt, List<Long> projectIds, List<String> tagCodes) {
        this(displayName, nickname, mobile, ownerStaffId, cityCode, sourceCode, channelCode, customerStatus,
                tierCode, intentLevel, spendingLevel, birthday, attentionProjects, blockerNote, remark, remarkName,
                firstAddedAt, projectIds, tagCodes, null, null, null);
    }
}
