package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReassignTaskRequest(
        @NotNull Long assigneeUserId,
        @NotBlank(message = "转派原因不能为空") @Size(max = 500) String reason,
        @NotNull(message = "version 不能为空") Long version) {
}
