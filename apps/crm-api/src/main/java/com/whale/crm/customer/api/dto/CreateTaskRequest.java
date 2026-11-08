package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 新建任务（分配给自己）。taskType 走 taskTypes 字典；priority LOW/MEDIUM/HIGH，缺省 MEDIUM。
 */
public record CreateTaskRequest(
        Long customerId,
        @NotBlank(message = "任务类型不能为空") @Size(max = 48) String taskType,
        @Pattern(regexp = "LOW|MEDIUM|HIGH", message = "priority 仅支持 LOW/MEDIUM/HIGH") String priority,
        @NotBlank(message = "任务标题不能为空") @Size(max = 255) String title,
        @Size(max = 2000) String description,
        Instant dueAt) {
}
