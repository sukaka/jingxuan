package com.whale.crm.ai.domain;

import java.time.Instant;

public record KnowledgeDocument(Long id, Long tenantId, Long materialId, Long activeRevisionId, String datasetId,
                                String difyDocumentId, String status, Long publishedBy, Instant publishedAt,
                                Instant createdAt, Instant updatedAt, Long version) {
}
