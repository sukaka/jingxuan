package com.whale.crm.integration.wecom.media.domain;

public record WecomMediaReference(int assetIndex, String sdkFileId, String mediaKind, String md5sum,
                                  Long fileSize, String fileName, String contentType) {
}
