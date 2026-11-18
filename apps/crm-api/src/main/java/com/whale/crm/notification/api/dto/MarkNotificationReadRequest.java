package com.whale.crm.notification.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MarkNotificationReadRequest(@NotNull @Min(0) Long version) {
}
