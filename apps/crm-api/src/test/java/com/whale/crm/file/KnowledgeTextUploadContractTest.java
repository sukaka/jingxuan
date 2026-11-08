package com.whale.crm.file;

import com.whale.crm.file.api.dto.UploadInitRequest;
import com.whale.crm.file.application.FileContentInspector;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeTextUploadContractTest {
    private final FileContentInspector inspector = new FileContentInspector();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void markdownIsAcceptedAndDetectedWithoutChangingMime() throws Exception {
        Path file = Files.createTempFile("knowledge-guide", ".md");
        try {
            Files.writeString(file, "# 合成服务流程\n仅用于文本上传测试");
            FileContentInspector.Inspection inspection = inspector.inspect(file, true);

            assertThat(inspection.mimeType()).isEqualTo("text/markdown");
            assertThat(inspection.sizeBytes()).isGreaterThan(0);
            assertThat(validator.validate(new UploadInitRequest("guide.md", "text/markdown", 30L,
                    "a".repeat(64), "MATERIAL", 1L))).isEmpty();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void declaredMarkdownDoesNotRequireMarkupAndTextRemainsMaterialOnly() throws Exception {
        Path text = Files.createTempFile("knowledge-plain-markdown", ".md");
        try {
            Files.writeString(text, "没有标题标记的合成 Markdown 正文");
            assertThat(inspector.inspect(text, true, "text/markdown").mimeType()).isEqualTo("text/markdown");
            assertThatThrownBy(() -> inspector.inspect(text, false, "text/plain"))
                    .isInstanceOfSatisfying(FileContentInspector.FileContentException.class,
                            error -> assertThat(error.code()).isEqualTo("UNSUPPORTED_CONTENT"));
        } finally {
            Files.deleteIfExists(text);
        }
    }

    @Test
    void plainTextAndMarkdownAreAcceptedButInvalidUtf8IsRejected() throws Exception {
        Path text = Files.createTempFile("knowledge-text", ".txt");
        Path invalid = Files.createTempFile("knowledge-invalid", ".txt");
        try {
            Files.writeString(text, "普通合成知识文本");
            assertThat(inspector.inspect(text, true).mimeType()).isEqualTo("text/plain");

            Files.write(invalid, new byte[]{(byte) 0xC3, 0x28});
            assertThatThrownBy(() -> inspector.inspect(invalid, true))
                    .isInstanceOf(FileContentInspector.FileContentException.class);
        } finally {
            Files.deleteIfExists(text);
            Files.deleteIfExists(invalid);
        }
    }
}
