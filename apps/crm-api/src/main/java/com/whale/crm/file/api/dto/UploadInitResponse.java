package com.whale.crm.file.api.dto;

import java.time.Instant;

public record UploadInitResponse(Long fileId, String objectKey, String uploadUrl,
                                 Instant expiresAt, String method, String sha256) {
}
