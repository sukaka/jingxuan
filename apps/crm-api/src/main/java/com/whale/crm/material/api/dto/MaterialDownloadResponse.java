package com.whale.crm.material.api.dto;

import java.time.Instant;

public record MaterialDownloadResponse(Long materialId, Long revisionId, Long fileId, String downloadUrl,
                                       Instant expiresAt) {
}
