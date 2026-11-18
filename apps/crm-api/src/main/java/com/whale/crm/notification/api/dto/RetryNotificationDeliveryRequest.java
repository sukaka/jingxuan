package com.whale.crm.notification.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RetryNotificationDeliveryRequest(@NotNull @Min(0) Long version,
                                                @Size(max = 500) String reason) {
}
