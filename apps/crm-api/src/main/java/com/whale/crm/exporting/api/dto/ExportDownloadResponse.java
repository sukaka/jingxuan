package com.whale.crm.exporting.api.dto;

import java.time.Instant;

public record ExportDownloadResponse(Long fileId, String downloadUrl, Instant expiresAt) {
}
