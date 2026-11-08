package com.whale.crm.refund.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateRefundRequest(
        @NotNull Long orderId,
        @NotNull @Min(1) Long requestedFen,
        @NotBlank @Size(max = 64) String reasonCode,
        @Size(max = 1000) String reasonDetail,
        @NotEmpty List<@Valid CreateRefundItemRequest> items) {
    public CreateRefundRequest {
        items = items == null ? null : List.copyOf(items);
    }
}
