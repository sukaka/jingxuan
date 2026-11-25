package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 编辑客户（PATCH 白名单）。未传的字段保持不变（null 语义 = 不修改）；
 * mobile 传空字符串表示清除手机号。version 乐观锁，失配 409。
 * tierCode 仅为识别旧客户端请求而保留，非 null 一律拒绝；等级走专用分级接口。
 * attentionProjects 为只读 legacy 文本；projectIds 为空列表时清空正规关注关系，null 时不变。
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
        @Size(max = 100) List<Long> projectIds) {
    public UpdateCustomerRequest {
        projectIds = projectIds == null ? null : List.copyOf(projectIds);
    }
}
