package com.whale.crm.file;

import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.application.FileContentInspector;
import com.whale.crm.file.infrastructure.FileProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileInfrastructureProtocolTest {
    private final FileContentInspector inspector = new FileContentInspector();

    @Test
    void inspectionComputesRealSha256AndAcceptsValidPdf() throws Exception {
        Path path = Files.createTempFile("file-inspection-", ".pdf");
        try {
            byte[] content = SyntheticFileFixtures.cleanPdf();
            Files.write(path, content);

            FileContentInspector.Inspection inspection = inspector.inspect(path);

            assertThat(inspection.sizeBytes()).isEqualTo(content.length);
            assertThat(inspection.mimeType()).isEqualTo("application/pdf");
            String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            assertThat(inspection.sha256()).isEqualTo(expectedSha);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void invalidOrTruncatedPdfIsRejected() throws Exception {
        Path path = Files.createTempFile("file-inspection-invalid-", ".pdf");
        try {
            Files.write(path, SyntheticFileFixtures.bytes("%PDF-1.4\n"));

            assertThatThrownBy(() -> inspector.inspect(path))
                    .isInstanceOf(FileContentInspector.FileContentException.class)
                    .extracting(ex -> ((FileContentInspector.FileContentException) ex).code())
                    .isEqualTo("INVALID_PDF");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void inspectionAcceptsUtf8CsvWithQuotedContentAndComputesRealHash() throws Exception {
        Path path = Files.createTempFile("file-inspection-", ".csv");
        try {
            byte[] content = "客户编号,备注\r\nC-1,\"包含,逗号与\"\"引号\"\"\"\r\nC-2,'=公式已转义\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(path, content);

            FileContentInspector.Inspection inspection = inspector.inspect(path);

            assertThat(inspection.mimeType()).isEqualTo("text/csv");
            assertThat(inspection.sizeBytes()).isEqualTo(content.length);
            String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            assertThat(inspection.sha256()).isEqualTo(expectedSha);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void inspectionRejectsMalformedUtf8OrUnclosedCsvQuotes() throws Exception {
        Path path = Files.createTempFile("file-inspection-invalid-", ".csv");
        try {
            Files.write(path, new byte[]{(byte) 0xC3, (byte) 0x28, ',', '\r', '\n'});
            assertThatThrownBy(() -> inspector.inspect(path))
                    .isInstanceOf(FileContentInspector.FileContentException.class)
                    .extracting(ex -> ((FileContentInspector.FileContentException) ex).code())
                    .isEqualTo("INVALID_CSV");

            Files.writeString(path, "code,note\r\nC-1,\"unclosed\r\n", java.nio.charset.StandardCharsets.UTF_8);
            assertThatThrownBy(() -> inspector.inspect(path))
                    .isInstanceOf(FileContentInspector.FileContentException.class)
                    .extracting(ex -> ((FileContentInspector.FileContentException) ex).code())
                    .isEqualTo("INVALID_CSV");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void clamAvCleanUsesRealInstreamProtocol() throws Exception {
        Path path = Files.createTempFile("clamav-clean-", ".pdf");
        try (ClamAvTestServer server = new ClamAvTestServer("stream: OK\0")) {
            byte[] content = SyntheticFileFixtures.cleanPdf();
            Files.write(path, content);
            ClamAvScanner scanner = new ClamAvScanner(server.host(), server.port(), 2000, 2000);

            assertThat(scanner.scan(path)).isEqualTo(ClamAvScanner.ScanResult.CLEAN);

            ClamAvTestServer.Receipt receipt = server.awaitReceipt();
            assertThat(receipt.command()).isEqualTo("zINSTREAM");
            assertThat(receipt.content()).isEqualTo(content);
            assertThat(receipt.chunkSizes()).containsExactly(content.length);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void clamAvInstreamUsesBigEndianChunkLengthsAndZeroTerminator() throws Exception {
        Path path = Files.createTempFile("clamav-chunks-", ".dat");
        try (ClamAvTestServer server = new ClamAvTestServer("stream: OK\0")) {
            byte[] content = new byte[150000];
            for (int index = 0; index < content.length; index++) {
                content[index] = (byte) (index % 251);
            }
            Files.write(path, content);
            ClamAvScanner scanner = new ClamAvScanner(server.host(), server.port(), 2000, 2000);

            assertThat(scanner.scan(path)).isEqualTo(ClamAvScanner.ScanResult.CLEAN);

            ClamAvTestServer.Receipt receipt = server.awaitReceipt();
            assertThat(receipt.command()).isEqualTo("zINSTREAM");
            assertThat(receipt.chunkSizes()).containsExactly(65536, 65536, 18928);
            assertThat(receipt.content()).isEqualTo(content);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void clamAvFoundIsNotClean() throws Exception {
        Path path = Files.createTempFile("clamav-found-", ".dat");
        try (ClamAvTestServer server = new ClamAvTestServer("stream: Eicar-Test-Signature FOUND\0")) {
            Files.write(path, SyntheticFileFixtures.bytes(SyntheticFileFixtures.EICAR));
            ClamAvScanner scanner = new ClamAvScanner(server.host(), server.port(), 2000, 2000);

            assertThat(scanner.scan(path)).isEqualTo(ClamAvScanner.ScanResult.FOUND);
            assertThat(server.awaitReceipt().command()).isEqualTo("zINSTREAM");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void clamAvRejectsNonLoopbackTcpBeforeConnecting() {
        assertThatThrownBy(() -> new ClamAvScanner("clamav.example.invalid", 3310, 10, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("本机");
    }

    @Test
    void clamAvErrorIsNotCleanAndRemoteFilesDefaultDisabled() throws Exception {
        Path path = Files.createTempFile("clamav-error-", ".dat");
        try (ClamAvTestServer server = new ClamAvTestServer("stream: test ERROR\0")) {
            Files.write(path, SyntheticFileFixtures.bytes("test"));
            ClamAvScanner scanner = new ClamAvScanner(server.host(), server.port(), 2000, 2000);

            assertThat(scanner.scan(path)).isEqualTo(ClamAvScanner.ScanResult.ERROR);
            assertThat(new FileProperties().isEnabled()).isFalse();
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
