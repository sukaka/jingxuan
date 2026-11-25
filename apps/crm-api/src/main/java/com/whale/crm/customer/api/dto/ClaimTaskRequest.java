package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;

public record ClaimTaskRequest(@NotNull(message = "数据版本号不能为空") Long version) {
}
