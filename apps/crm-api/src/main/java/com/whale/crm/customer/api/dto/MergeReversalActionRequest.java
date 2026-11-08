package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MergeReversalActionRequest(@NotBlank @Size(max = 1000) String reason,
                                         Long version) {
}
