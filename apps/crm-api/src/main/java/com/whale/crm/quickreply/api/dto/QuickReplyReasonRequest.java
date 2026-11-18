package com.whale.crm.quickreply.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record QuickReplyReasonRequest(@NotNull @PositiveOrZero Long version,
                                      @NotBlank @Size(max = 1000) String reason) {
}
