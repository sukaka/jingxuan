package com.whale.crm.ai.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PublishKnowledgeDocumentRequest(
        @NotNull @Min(1) Long materialId,
        @NotNull @Min(1) Long revisionId,
        @NotNull @Min(0) Long version) {
}
