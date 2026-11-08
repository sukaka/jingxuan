package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 修改任务普通字段（本人/同组主管/管理员）。status 仅用于显式拒绝旧客户端，流转必须走动作接口。
 */
public record UpdateTaskRequest(
        @Size(max = 48) String taskType,
        @Pattern(regexp = "LOW|MEDIUM|HIGH|URGENT", message = "priority 仅支持 LOW/MEDIUM/HIGH/URGENT") String priority,
        @Pattern(regexp = "OPEN|PROCESSING|DONE|CANCELED", message = "status 仅支持 OPEN/PROCESSING/DONE/CANCELED") String status,
        @Size(max = 255) String title,
        @Size(max = 2000) String description,
        Instant dueAt,
        @NotNull(message = "version 不能为空") Long version) {
}
