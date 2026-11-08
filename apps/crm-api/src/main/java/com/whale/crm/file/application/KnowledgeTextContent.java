package com.whale.crm.file.application;

import com.whale.crm.file.domain.FileMetadata;

public record KnowledgeTextContent(FileMetadata metadata, String text) {
}
