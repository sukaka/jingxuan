package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 新建客户。nickname/displayName 至少其一（Service 校验）；mobile 可空（C01）；
 * customerNo 由服务端生成，客户端传入一律忽略。
 * ownerStaffId 可选：MANAGER/MANAGER_LEAD 缺省默认本人；其他角色缺省 400 必须显式选择；
 * 显式时校验目标为在职管家/主管且在当前用户可分配范围内。
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
        Instant firstAddedAt) {
}
