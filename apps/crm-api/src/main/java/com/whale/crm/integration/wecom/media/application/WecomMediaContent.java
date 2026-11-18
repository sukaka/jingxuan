package com.whale.crm.integration.wecom.media.application;

import java.io.InputStream;

public record WecomMediaContent(InputStream input, String contentType, String fileName, long sizeBytes) {
}
