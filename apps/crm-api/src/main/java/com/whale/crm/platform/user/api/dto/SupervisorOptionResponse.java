package com.whale.crm.platform.user.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 直属上级可选员工项（仅返回启用状态的内部员工）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorOptionResponse(
        Long id,
        String username,
        String displayName,
        Long teamId,
        String teamName) {
}
