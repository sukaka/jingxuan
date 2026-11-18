package com.whale.crm.integration.wecom.api.dto;

import com.whale.crm.integration.wecom.media.api.dto.WecomMediaAssetView;

import java.time.Instant;
import java.util.List;

public record WecomContentView(Long messageRef, String messageType, Instant sentAt, String content,
                               List<WecomMediaAssetView> mediaAssets, Instant recalledAt) {
    public WecomContentView {
        mediaAssets = mediaAssets == null ? List.of() : List.copyOf(mediaAssets);
    }
}
