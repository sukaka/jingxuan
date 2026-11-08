package com.whale.crm.platform.user.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 覆盖式分配角色。空列表表示摘除全部角色（权限对下一请求立即失效）。
 */
public record AssignRolesRequest(
        @NotNull(message = "roleIds 必填") List<@Positive(message = "roleId 无效") Long> roleIds) {
    public AssignRolesRequest {
        roleIds = roleIds == null ? null : List.copyOf(roleIds);
    }
}
