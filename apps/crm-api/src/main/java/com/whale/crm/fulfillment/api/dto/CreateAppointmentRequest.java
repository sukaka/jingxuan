package com.whale.crm.fulfillment.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record CreateAppointmentRequest(@NotNull Long customerId, @NotNull Long institutionId,
                                       @NotNull Instant scheduledAt, @Size(max = 1000) String remark,
                                       @NotEmpty List<@Valid AppointmentItemRequest> items) {
    public CreateAppointmentRequest {
        items = items == null ? null : List.copyOf(items);
    }
}
