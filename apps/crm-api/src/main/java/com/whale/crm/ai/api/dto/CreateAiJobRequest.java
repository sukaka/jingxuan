package com.whale.crm.ai.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateAiJobRequest(@NotEmpty @Size(max = 50) List<Long> messageIds) {
    public CreateAiJobRequest {
        messageIds = messageIds == null ? null : List.copyOf(messageIds);
    }
}
