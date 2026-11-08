package com.whale.crm.file.api.dto;

import java.time.Instant;

public record DownloadResponse(Long fileId, String downloadUrl, Instant expiresAt) {
}
