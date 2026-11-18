package com.whale.crm.quickreply.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record QuickReplyPreviewRequest(@NotNull @Positive Long customerId,
                                       @Size(max = 64) String scene) {
}
