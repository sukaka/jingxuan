package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 编辑客户（PATCH 白名单）。未传的字段保持不变（null 语义 = 不修改）；
 * mobile 传空字符串表示清除手机号。version 乐观锁，失配 409。
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
        @NotNull(message = "version 不能为空") Long version) {
}
