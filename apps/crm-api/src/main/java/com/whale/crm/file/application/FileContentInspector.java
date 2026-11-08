package com.whale.crm.file.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class FileContentInspector {

    public Inspection inspect(Path path) throws IOException, FileContentException {
        return inspect(path, false, null);
    }

    public Inspection inspect(Path path, boolean allowText) throws IOException, FileContentException {
        return inspect(path, allowText, null);
    }

    public Inspection inspect(Path path, boolean allowText, String declaredMimeType) throws IOException, FileContentException {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            // nosemgrep: java.lang.security.audit.crypto.use-of-md5.use-of-md5 -- S3/MinIO ETag 协议值，SHA-256 同时用于完整性校验
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            long size = 0;
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        sha256.update(buffer, 0, read);
                        md5.update(buffer, 0, read);
                        size = Math.addExact(size, read);
                    }
                }
            }
            if (size == 0) {
                throw new FileContentException("EMPTY_FILE");
            }
            byte[] header = header(path);
            String mimeType = detectMimeType(header);
            boolean declaredMarkdown = "text/markdown".equals(declaredMimeType) || "text/x-markdown".equals(declaredMimeType);
            if (allowText && mimeType == null && (declaredMarkdown || looksMarkdown(path))) {
                mimeType = detectText(path, declaredMarkdown ? Boolean.TRUE : null);
            }
            if (mimeType == null) {
                mimeType = detectCsv(path);
            }
            if (allowText && mimeType == null) {
                mimeType = detectText(path);
            }
            if (mimeType == null) {
                throw new FileContentException("UNSUPPORTED_CONTENT");
            }
            if ("application/pdf".equals(mimeType) && !validPdf(path, header)) {
                throw new FileContentException("INVALID_PDF");
            }
            return new Inspection(size, HexFormat.of().formatHex(sha256.digest()),
                    HexFormat.of().formatHex(md5.digest()), mimeType);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Required digest algorithm unavailable", ex);
        }
    }

    private String detectMimeType(byte[] header) {
        if (startsWith(header, new byte[]{'%', 'P', 'D', 'F', '-' })) {
            return "application/pdf";
        }
        if (startsWith(header, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (startsWith(header, png)) {
            return "image/png";
        }
        return null;
    }

    private boolean looksMarkdown(Path path) throws IOException, FileContentException {
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            int value;
            while ((value = reader.read()) >= 0) {
                if (!Character.isWhitespace(value)) {
                    return value == '#' || value == '>' || value == '-' || value == '*' || value == '[';
                }
            }
            return false;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    private String detectText(Path path) throws IOException, FileContentException {
        return detectText(path, null);
    }

    private String detectText(Path path, Boolean markdownHint) throws IOException, FileContentException {
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] buffer = new char[4096];
            Character firstContent = null;
            boolean markdown = Boolean.TRUE.equals(markdownHint);
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    char value = buffer[index];
                    if (Character.isISOControl(value) && value != '\t' && value != '\r' && value != '\n') {
                        throw new FileContentException("UNSUPPORTED_CONTENT");
                    }
                    if (firstContent == null && !Character.isWhitespace(value)) {
                        firstContent = value;
                        if (markdownHint == null) {
                            markdown = value == '#' || value == '>' || value == '-' || value == '*' || value == '[';
                        }
                    }
                }
            }
            if (firstContent == null) {
                return null;
            }
            return markdown ? "text/markdown" : "text/plain";
        } catch (CharacterCodingException ex) {
            throw new FileContentException("UNSUPPORTED_CONTENT");
        }
    }

    private String detectCsv(Path path) throws IOException, FileContentException {
        boolean inQuotes = false;
        boolean quotePending = false;
        boolean closedQuote = false;
        boolean fieldStart = true;
        boolean structured = false;
        long position = 0;
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] buffer = new char[64 * 1024];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    char value = buffer[index];
                    position++;
                    if (position == 1 && value == '\uFEFF') {
                        continue;
                    }
                    if (Character.isISOControl(value) && value != '\t' && value != '\r' && value != '\n') {
                        throw new FileContentException("INVALID_CSV");
                    }
                    if (inQuotes) {
                        if (quotePending) {
                            if (value == '"') {
                                quotePending = false;
                                continue;
                            }
                            inQuotes = false;
                            quotePending = false;
                            closedQuote = true;
                        } else {
                            if (value == '"') {
                                quotePending = true;
                            }
                            continue;
                        }
                    }
                    if (closedQuote) {
                        if (value == ',' || isLineBreak(value)) {
                            structured = true;
                            fieldStart = true;
                            closedQuote = false;
                            continue;
                        }
                        throw new FileContentException("INVALID_CSV");
                    }
                    if (value == '"') {
                        if (!fieldStart) {
                            throw new FileContentException("INVALID_CSV");
                        }
                        inQuotes = true;
                        fieldStart = false;
                    } else if (value == ',' || isLineBreak(value)) {
                        structured = true;
                        fieldStart = true;
                    } else {
                        fieldStart = false;
                    }
                }
            }
        } catch (CharacterCodingException ex) {
            throw new FileContentException("INVALID_CSV");
        }
        if (quotePending) {
            inQuotes = false;
        }
        if (inQuotes) {
            throw new FileContentException("INVALID_CSV");
        }
        return structured ? "text/csv" : null;
    }

    private boolean isLineBreak(char value) {
        return value == '\r' || value == '\n';
    }

    private boolean validPdf(Path path, byte[] header) throws IOException {
        if (header.length < 8 || header[5] < '0' || header[5] > '9') {
            return false;
        }
        byte[] tail = tail(path);
        String tailText = new String(tail, StandardCharsets.ISO_8859_1);
        return tailText.contains("%%EOF");
    }

    private byte[] header(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(32);
        }
    }

    private byte[] tail(Path path) throws IOException {
        long size = Files.size(path);
        int length = (int) Math.min(1024, size);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(size - length);
            byte[] tail = new byte[length];
            file.readFully(tail);
            return tail;
        }
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    public record Inspection(long sizeBytes, String sha256, String md5, String mimeType) {
    }

    public static class FileContentException extends Exception {
        private final String code;

        public FileContentException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
