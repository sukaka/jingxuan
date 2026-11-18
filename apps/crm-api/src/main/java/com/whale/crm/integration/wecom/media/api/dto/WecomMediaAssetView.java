package com.whale.crm.integration.wecom.media.api.dto;

public record WecomMediaAssetView(long id, String mediaKind, String fileName, String contentType, Long fileSize,
                                  String status, boolean available) {
}
