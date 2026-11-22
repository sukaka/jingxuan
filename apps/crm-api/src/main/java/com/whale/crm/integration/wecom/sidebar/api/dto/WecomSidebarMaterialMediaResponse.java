package com.whale.crm.integration.wecom.sidebar.api.dto;

import java.time.Instant;

/**
 * 企微侧边栏素材库文件换取企微临时素材 media_id 响应。
 */
public record WecomSidebarMaterialMediaResponse(
        long materialId,
        long revisionId,
        long fileId,
        String mediaId,
        String mediaType,
        String fileName,
        String contentType,
        long sizeBytes,
        boolean cached,
        Instant expiresAt) {
}
