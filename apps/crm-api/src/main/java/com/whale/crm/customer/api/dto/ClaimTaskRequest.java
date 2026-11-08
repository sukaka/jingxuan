package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotNull;

public record ClaimTaskRequest(@NotNull(message = "version 不能为空") Long version) {
}
