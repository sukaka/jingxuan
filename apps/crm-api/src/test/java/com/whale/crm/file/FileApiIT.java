package com.whale.crm.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.files.enabled=true",
        "app.files.endpoint=http://127.0.0.1:9",
        "app.files.access-key=synthetic-test-only",
        "app.files.secret-key=synthetic-test-only",
        "app.files.upload-expiry-seconds=43",
        "app.files.download-expiry-seconds=37",
        "app.files.max-file-size-bytes=4096",
        "app.outbox.relay.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileApiIT extends AbstractMysqlIT {
    private static final long TENANT_ID = 1L;
    private static final long INSTITUTION_ID = 1L;
    private static final long OTHER_INSTITUTION_ID = 2L;
    private static final int DOWNLOAD_EXPIRY_SECONDS = 37;

    @MockitoBean
    private PrivateObjectStorage storage;

    @MockitoBean
    private ClamAvScanner scanner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, PrivateObjectStorage.ObjectStat> statOverrides = new HashMap<>();

    private AuthCookies business;
    private AuthCookies operator1;
    private AuthCookies operator2;

    @BeforeAll
    void loginUsers() throws Exception {
        business = login("business1");
        operator1 = login("operator1");
        operator2 = login("operator2");
    }

    @BeforeEach
    void configureControlledStorage() throws Exception {
        objects.clear();
        statOverrides.clear();
        when(storage.presignPut(anyString(), anyInt())).thenAnswer(invocation ->
                "https://storage.invalid/upload?object=" + invocation.getArgument(0)
                        + "&X-Amz-Expires=" + invocation.getArgument(1) + "&X-Amz-Signature=synthetic");
        when(storage.stat(anyString())).thenAnswer(invocation -> objectStat(invocation.getArgument(0)));
        when(storage.getObject(anyString())).thenAnswer(invocation -> {
            byte[] content = objects.get(invocation.getArgument(0));
            if (content == null) {
                throw new IllegalStateException("Synthetic object was not uploaded");
            }
            return new ByteArrayInputStream(content);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            objects.put(invocation.getArgument(0), ((java.io.InputStream) invocation.getArgument(1)).readAllBytes());
            return null;
        }).when(storage).putObject(anyString(), any(java.io.InputStream.class), org.mockito.ArgumentMatchers.anyLong(), anyString());
        org.mockito.Mockito.doAnswer(invocation -> {
            objects.remove(invocation.getArgument(0));
            return null;
        }).when(storage).deleteObject(anyString());
        when(storage.presignGet(anyString(), anyInt())).thenAnswer(invocation ->
                "https://storage.invalid/download?object=" + invocation.getArgument(0)
                        + "&X-Amz-Expires=" + invocation.getArgument(1) + "&X-Amz-Signature=synthetic");
        when(scanner.scan(any())).thenReturn(ClamAvScanner.ScanResult.CLEAN);
    }

    @Test
    void uploadInitRejectsInvalidMimeAndOversizeWithoutCreatingMetadata() throws Exception {
        byte[] pdf = SyntheticFileFixtures.cleanPdf();
        String invalidMimeName = uniqueName("invalid-mime");
        String oversizeName = uniqueName("oversize");

        mockMvc.perform(initRequest(business, invalidMimeName, "image/png", pdf.length,
                        sha256(pdf), "CONTRACT", INSTITUTION_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(initRequest(business, oversizeName, "application/pdf", 4097,
                        sha256(pdf), "CONTRACT", INSTITUTION_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(countFilesNamed(invalidMimeName)).isZero();
        assertThat(countFilesNamed(oversizeName)).isZero();
        verify(storage, never()).presignPut(anyString(), anyInt());
    }

    @Test
    void pendingAndMissingObjectsCannotBecomeCleanOrBeReferenced() throws Exception {
        byte[] pdf = SyntheticFileFixtures.cleanPdf();
        Upload upload = initUpload(pdf);

        assertThat(scanStatus(upload.id())).isEqualTo("PENDING");
        assertUnusable(upload);

        mockMvc.perform(completeRequest(upload, md5Etag(pdf)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertFileError(upload, "STORAGE_ERROR");
        assertUnusable(upload);
        verify(scanner, never()).scan(any());
    }

    @Test
    void etagHashAndSizeMismatchesCannotBecomeClean() throws Exception {
        byte[] declared = SyntheticFileFixtures.pdfWithComment("A");
        byte[] sameSizeDifferentContent = SyntheticFileFixtures.pdfWithComment("B");
        assertThat(sameSizeDifferentContent).hasSameSizeAs(declared);

        Upload hashMismatch = initUpload(declared);
        putObject(hashMismatch, sameSizeDifferentContent);
        mockMvc.perform(completeRequest(hashMismatch, md5Etag(sameSizeDifferentContent)))
                .andExpect(status().isConflict());
        assertFileError(hashMismatch, "HASH_MISMATCH");

        Upload etagMismatch = initUpload(declared);
        putObject(etagMismatch, declared);
        statOverrides.put(etagMismatch.objectKey(), new PrivateObjectStorage.ObjectStat(
                "00000000000000000000000000000000", declared.length, "application/pdf"));
        mockMvc.perform(completeRequest(etagMismatch, md5Etag(declared)))
                .andExpect(status().isConflict());
        assertFileError(etagMismatch, "ETAG_MISMATCH");

        Upload sizeMismatch = initUpload(declared, declared.length + 1L);
        putObject(sizeMismatch, declared);
        mockMvc.perform(completeRequest(sizeMismatch, md5Etag(declared)))
                .andExpect(status().isConflict());
        assertFileError(sizeMismatch, "SIZE_MISMATCH");

        verify(scanner, never()).scan(any());
    }

    @Test
    void invalidPdfAndActualMimeMismatchCannotBecomeClean() throws Exception {
        byte[] invalidPdf = SyntheticFileFixtures.bytes("%PDF-1.4\ntruncated synthetic file");
        Upload truncated = initUpload(invalidPdf);
        putObject(truncated, invalidPdf);
        mockMvc.perform(completeRequest(truncated, md5Etag(invalidPdf)))
                .andExpect(status().isConflict());
        assertFileError(truncated, "INVALID_PDF");

        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        Upload mimeMismatch = initUpload(png, "application/pdf", png.length);
        putObject(mimeMismatch, png);
        mockMvc.perform(completeRequest(mimeMismatch, md5Etag(png)))
                .andExpect(status().isConflict());
        assertFileError(mimeMismatch, "MIME_MISMATCH");

        byte[] unsupported = SyntheticFileFixtures.bytes("synthetic non-PDF content");
        Upload unsupportedContent = initUpload(unsupported);
        putObject(unsupportedContent, unsupported);
        mockMvc.perform(completeRequest(unsupportedContent, md5Etag(unsupported)))
                .andExpect(status().isConflict());
        assertFileError(unsupportedContent, "UNSUPPORTED_CONTENT");

        verify(scanner, never()).scan(any());
    }

    @Test
    void scannerFoundIsBlockedAndCannotBeReferenced() throws Exception {
        byte[] eicarPdf = SyntheticFileFixtures.eicarPdf();
        Upload upload = initUpload(eicarPdf);
        putObject(upload, eicarPdf);
        when(scanner.scan(any())).thenAnswer(invocation -> {
            assertThat(Files.readAllBytes(invocation.getArgument(0))).isEqualTo(eicarPdf);
            return ClamAvScanner.ScanResult.FOUND;
        });

        mockMvc.perform(completeRequest(upload, md5Etag(eicarPdf)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(scanStatus(upload.id())).isEqualTo("BLOCKED");
        assertThat(fileRow(upload.id()).get("last_error")).isEqualTo("CLAMAV_FOUND");
        assertUnusable(upload);
        verify(scanner).scan(any());
    }

    @Test
    void scannerErrorCannotBeReferenced() throws Exception {
        byte[] pdf = SyntheticFileFixtures.cleanPdf();
        Upload upload = initUpload(pdf);
        putObject(upload, pdf);
        when(scanner.scan(any())).thenReturn(ClamAvScanner.ScanResult.ERROR);

        mockMvc.perform(completeRequest(upload, md5Etag(pdf)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertFileError(upload, "CLAMAV_ERROR");
        assertUnusable(upload);
        verify(scanner).scan(any());
    }

    @Test
    void validPdfBecomesCleanOnlyAfterValidationAndScanAndCanBeReferenced() throws Exception {
        byte[] pdf = SyntheticFileFixtures.cleanPdf();
        Upload upload = initUpload(pdf);
        putObject(upload, pdf);
        when(scanner.scan(any())).thenAnswer(invocation -> {
            assertThat(Files.readAllBytes(invocation.getArgument(0))).isEqualTo(pdf);
            return ClamAvScanner.ScanResult.CLEAN;
        });

        JsonNode completed = data(completeRequest(upload, md5Etag(pdf)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanStatus").value("CLEAN"))
                .andReturnData();

        assertThat(completed.at("/sha256").asText()).isEqualTo(sha256(pdf));
        assertThat(completed.at("/sizeBytes").asLong()).isEqualTo(pdf.length);
        assertThat(completed.at("/mimeType").asText()).isEqualTo("application/pdf");
        assertThat(completed.at("/version").asLong()).isEqualTo(3L);
        Map<String, Object> row = fileRow(upload.id());
        assertThat(row.get("scan_status")).isEqualTo("CLEAN");
        assertThat(row.get("sha256")).isEqualTo(sha256(pdf));
        assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(pdf.length);
        assertThat(row.get("etag")).isEqualTo(md5Etag(pdf));
        assertThat(row.get("last_error")).isNull();
        verify(scanner).scan(any());

        String contractNo = "IT-FILE-CLEAN-" + UUID.randomUUID();
        data(contractRequest(upload, contractNo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fileId").value(upload.id()))
                .andExpect(jsonPath("$.data.versionNo").value(1));
        assertThat(countContracts(upload.id())).isEqualTo(1);
    }

    @Test
    void institutionScopeDownloadAuditAndSignedUrlExpiryAreEnforced() throws Exception {
        byte[] pdf = SyntheticFileFixtures.cleanPdf();
        Upload upload = initUpload(pdf);
        putObject(upload, pdf);
        data(completeRequest(upload, md5Etag(pdf))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/files/{id}", upload.id()).cookie(operator1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(upload.id()))
                .andExpect(jsonPath("$.data.scanStatus").value("CLEAN"));
        mockMvc.perform(get("/api/v1/files/{id}", upload.id()).cookie(operator2.cookies()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/files/{id}/download", upload.id()).cookie(operator2.cookies()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Instant before = Instant.now();
        JsonNode download = data(get("/api/v1/files/{id}/download", upload.id()).cookie(operator1.cookies()))
                .andExpect(status().isOk())
                .andReturnData();
        Instant after = Instant.now();
        String downloadUrl = download.at("/downloadUrl").asText();
        Instant expiresAt = Instant.parse(download.at("/expiresAt").asText());
        assertThat(downloadUrl).contains("X-Amz-Expires=" + DOWNLOAD_EXPIRY_SECONDS);
        assertThat(expiresAt).isBetween(before.plusSeconds(DOWNLOAD_EXPIRY_SECONDS),
                after.plusSeconds(DOWNLOAD_EXPIRY_SECONDS));
        verify(storage, times(1)).presignGet((String) fileRow(upload.id()).get("object_key"), DOWNLOAD_EXPIRY_SECONDS);

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT after_json FROM crm_audit_log WHERE tenant_id=? AND action_code='FILE_DOWNLOAD' "
                        + "AND target_type='FILE' AND target_id=? AND actor_user_id=10",
                TENANT_ID, Long.toString(upload.id()));
        assertThat(audit.get("after_json").toString()).contains("CLEAN")
                .doesNotContain(downloadUrl).doesNotContain(upload.objectKey()).doesNotContain("secret");
        assertThat(countDownloadAudits(upload.id(), 11L)).isZero();

        String crossInstitutionName = uniqueName("cross-institution-init");
        mockMvc.perform(initRequest(operator2, crossInstitutionName, "application/pdf", pdf.length,
                        sha256(pdf), "CONTRACT", INSTITUTION_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(countFilesNamed(crossInstitutionName)).isZero();
    }

    @Test
    void cleanMetadataRejectsDifferentEtagAndSeparateInitUsesNewObjectKey() throws Exception {
        byte[] original = SyntheticFileFixtures.pdfWithComment("A");
        String originalName = uniqueName("immutable-version");
        Upload first = initUpload(originalName, original, "application/pdf", original.length);
        Upload second = initUpload(originalName, original, "application/pdf", original.length);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.objectKey()).isNotEqualTo(first.objectKey());

        putObject(first, original);
        data(completeRequest(first, md5Etag(original))).andExpect(status().isOk());
        Map<String, Object> cleanRow = fileRow(first.id());
        data(completeRequest(first, md5Etag(original)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanStatus").value("CLEAN"));

        byte[] attemptedReplacement = SyntheticFileFixtures.pdfWithComment("B");
        putObject(first, attemptedReplacement);
        mockMvc.perform(completeRequest(first, md5Etag(attemptedReplacement)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        Map<String, Object> afterAttempt = fileRow(first.id());
        assertThat(afterAttempt.get("object_key")).isNotEqualTo(first.objectKey());
        assertThat(objects.get(afterAttempt.get("object_key"))).isEqualTo(original);
        assertThat(afterAttempt.get("object_key")).isEqualTo(cleanRow.get("object_key"));
        assertThat(afterAttempt.get("sha256")).isEqualTo(cleanRow.get("sha256"));
        assertThat(afterAttempt.get("etag")).isEqualTo(cleanRow.get("etag"));
        assertThat(afterAttempt.get("version")).isEqualTo(cleanRow.get("version"));
        assertThat(scanStatus(second.id())).isEqualTo("PENDING");
        verify(scanner, times(1)).scan(any());
    }

    @Test
    void legacyCleanObjectIsRevalidatedAndSealedBeforeDownload() throws Exception {
        byte[] pdf = SyntheticFileFixtures.cleanPdf();
        Upload upload = initUpload(pdf);
        putObject(upload, pdf);
        data(completeRequest(upload, md5Etag(pdf))).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE crm_file_object SET object_key=?,version=version+1 WHERE tenant_id=? AND id=?",
                upload.objectKey(), TENANT_ID, upload.id());
        data(get("/api/v1/files/{id}/download", upload.id()).cookie(business.cookies())).andExpect(status().isOk());
        String sealed = (String) fileRow(upload.id()).get("object_key");
        assertThat(sealed).startsWith("sealed/").isNotEqualTo(upload.objectKey());
        assertThat(objects.get(sealed)).isEqualTo(pdf);
        verify(scanner, times(2)).scan(any());
    }

    @Test
    void concurrentLegacyReadsSharePublishedObjectAndDeleteLoser() throws Exception {
        byte[] pdf = SyntheticFileFixtures.cleanPdf();
        Upload upload = initUpload(pdf);
        putObject(upload, pdf);
        data(completeRequest(upload, md5Etag(pdf))).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE crm_file_object SET object_key=?,version=version+1 WHERE tenant_id=? AND id=?",
                upload.objectKey(), TENANT_ID, upload.id());
        CountDownLatch scanned = new CountDownLatch(2);
        when(scanner.scan(any())).thenAnswer(invocation -> {
            scanned.countDown();
            if (!scanned.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent scan did not arrive");
            }
            return ClamAvScanner.ScanResult.CLEAN;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> mockMvc.perform(get("/api/v1/files/{id}/download", upload.id())
                    .cookie(business.cookies())).andReturn());
            var second = executor.submit(() -> mockMvc.perform(get("/api/v1/files/{id}/download", upload.id())
                    .cookie(business.cookies())).andReturn());
            var firstResult = first.get(20, TimeUnit.SECONDS);
            var secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(firstResult.getResponse().getContentAsString())
                    .contains((String) fileRow(upload.id()).get("object_key"));
            assertThat(secondResult.getResponse().getContentAsString())
                    .contains((String) fileRow(upload.id()).get("object_key"));
        }
        verify(storage, times(1)).deleteObject(org.mockito.ArgumentMatchers.startsWith("sealed/"));
    }

    @Test
    void versionConflictAfterScanDeletesUnpublishedObject() throws Exception {
        byte[] pdf = SyntheticFileFixtures.cleanPdf();
        Upload upload = initUpload(pdf);
        putObject(upload, pdf);
        when(scanner.scan(any())).thenAnswer(invocation -> {
            jdbcTemplate.update("UPDATE crm_file_object SET version=version+1 WHERE tenant_id=? AND id=?",
                    TENANT_ID, upload.id());
            return ClamAvScanner.ScanResult.CLEAN;
        });
        mockMvc.perform(completeRequest(upload, md5Etag(pdf))).andExpect(status().isConflict());
        assertThat(scanStatus(upload.id())).isNotEqualTo("CLEAN");
        verify(storage).deleteObject(org.mockito.ArgumentMatchers.startsWith("sealed/"));
    }

    @Test
    void legacyTamperedObjectFailsClosed() throws Exception {
        byte[] pdf = SyntheticFileFixtures.pdfWithComment("A");
        Upload upload = initUpload(pdf);
        putObject(upload, pdf);
        data(completeRequest(upload, md5Etag(pdf))).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE crm_file_object SET object_key=?,version=version+1 WHERE tenant_id=? AND id=?",
                upload.objectKey(), TENANT_ID, upload.id());
        putObject(upload, SyntheticFileFixtures.pdfWithComment("B"));
        mockMvc.perform(get("/api/v1/files/{id}/download", upload.id()).cookie(business.cookies()))
                .andExpect(status().isConflict());
        assertThat(fileRow(upload.id()).get("object_key")).isEqualTo(upload.objectKey());
    }

    @Test
    void failedSealedWriteNeverMarksFileClean() throws Exception {
        byte[] pdf = SyntheticFileFixtures.cleanPdf();
        Upload upload = initUpload(pdf);
        putObject(upload, pdf);
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic failure")).when(storage)
                .putObject(anyString(), any(java.io.InputStream.class), org.mockito.ArgumentMatchers.anyLong(), anyString());
        mockMvc.perform(completeRequest(upload, md5Etag(pdf))).andExpect(status().isConflict());
        assertThat(scanStatus(upload.id())).isNotEqualTo("CLEAN");
        verify(storage).deleteObject(org.mockito.ArgumentMatchers.startsWith("sealed/"));
    }

    @Test
    void optimisticVersionConflictCannotOverwriteMetadataOrBecomeClean() throws Exception {
        byte[] pdf = SyntheticFileFixtures.cleanPdf();
        Upload upload = initUpload(pdf);
        putObject(upload, pdf);
        when(storage.stat(upload.objectKey())).thenAnswer(invocation -> {
            jdbcTemplate.update("UPDATE crm_file_object SET version=version+1 WHERE tenant_id=? AND id=?",
                    TENANT_ID, upload.id());
            return objectStat(upload.objectKey());
        });

        mockMvc.perform(completeRequest(upload, md5Etag(pdf)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(scanStatus(upload.id())).isNotEqualTo("CLEAN");
        assertThat(fileRow(upload.id()).get("etag")).isNull();
        verify(scanner, never()).scan(any());
        assertUnusable(upload);
    }

    @Test
    void materialMarkdownAliasAndPlainParagraphBecomeCleanWithNormalizedMime() throws Exception {
        Upload alias = initMaterial(SyntheticFileFixtures.bytes("# 合成知识标题"), "text/x-markdown");
        putObject(alias, SyntheticFileFixtures.bytes("# 合成知识标题"));
        mockMvc.perform(completeRequest(alias, md5Etag(SyntheticFileFixtures.bytes("# 合成知识标题"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mimeType").value("text/markdown"))
                .andExpect(jsonPath("$.data.scanStatus").value("CLEAN"));

        Upload paragraph = initMaterial(SyntheticFileFixtures.bytes("普通合成 Markdown 正文"), "text/markdown");
        putObject(paragraph, SyntheticFileFixtures.bytes("普通合成 Markdown 正文"));
        mockMvc.perform(completeRequest(paragraph, md5Etag(SyntheticFileFixtures.bytes("普通合成 Markdown 正文"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mimeType").value("text/markdown"))
                .andExpect(jsonPath("$.data.scanStatus").value("CLEAN"));
        verify(scanner, times(2)).scan(any());
    }

    @Test
    void automaticOutboxRelayRemainsDisabled() {
        assertThat(environment.getProperty("app.outbox.relay.enabled", Boolean.class)).isFalse();
    }

    private Upload initUpload(byte[] content) throws Exception {
        return initUpload(content, "application/pdf", content.length);
    }

    private Upload initUpload(byte[] content, long declaredSize) throws Exception {
        return initUpload(content, "application/pdf", declaredSize);
    }

    private Upload initUpload(byte[] content, String declaredMime, long declaredSize) throws Exception {
        return initUpload(uniqueName("upload"), content, declaredMime, declaredSize);
    }

    private Upload initUpload(String originalName, byte[] content, String declaredMime,
                              long declaredSize) throws Exception {
        JsonNode response = data(initRequest(business, originalName, declaredMime, declaredSize,
                        sha256(content), "CONTRACT", INSTITUTION_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.method").value("PUT"))
                .andReturnData();
        return new Upload(response.at("/fileId").asLong(), response.at("/objectKey").asText(),
                response.at("/sha256").asText(), response.at("/uploadUrl").asText(),
                Instant.parse(response.at("/expiresAt").asText()));
    }

    private Upload initMaterial(byte[] content, String declaredMime) throws Exception {
        JsonNode response = data(initRequest(business, uniqueName("material") + ".md", declaredMime, content.length,
                        sha256(content), "MATERIAL", INSTITUTION_ID))
                .andExpect(status().isCreated())
                .andReturnData();
        return new Upload(response.at("/fileId").asLong(), response.at("/objectKey").asText(),
                response.at("/sha256").asText(), response.at("/uploadUrl").asText(),
                Instant.parse(response.at("/expiresAt").asText()));
    }

    private MockHttpServletRequestBuilder initRequest(AuthCookies actor, String originalName, String mimeType,
                                                       long sizeBytes, String sha256, String classification,
                                                       long institutionId) throws Exception {
        return actor.withAuth(post("/api/v1/files/upload-init"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("originalName", originalName, "mimeType", mimeType,
                        "sizeBytes", sizeBytes, "sha256", sha256, "classification", classification,
                        "institutionId", institutionId)));
    }

    private MockHttpServletRequestBuilder completeRequest(Upload upload, String etag) throws Exception {
        return business.withAuth(post("/api/v1/files/upload-complete"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("fileId", upload.id(), "etag", etag)));
    }

    private MockHttpServletRequestBuilder contractRequest(Upload upload, String contractNo) throws Exception {
        return business.withAuth(post("/api/v1/institution-contracts"))
                .header("Idempotency-Key", "file-contract-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", INSTITUTION_ID, "contractNo", contractNo,
                        "title", "合成测试合同", "contractType", "SERVICE", "startDate", "2026-01-01",
                        "fileId", upload.id(), "amountFen", 0)));
    }

    private void assertUnusable(Upload upload) throws Exception {
        mockMvc.perform(get("/api/v1/files/{id}/download", upload.id()).cookie(business.cookies()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mockMvc.perform(contractRequest(upload, "IT-FILE-REJECT-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(countContracts(upload.id())).isZero();
    }

    private void assertFileError(Upload upload, String expectedError) {
        Map<String, Object> row = fileRow(upload.id());
        assertThat(row.get("scan_status")).isNotEqualTo("CLEAN");
        assertThat(row.get("last_error")).isEqualTo(expectedError);
    }

    private void putObject(Upload upload, byte[] content) {
        objects.put(upload.objectKey(), content);
    }

    private PrivateObjectStorage.ObjectStat objectStat(String objectKey) throws Exception {
        PrivateObjectStorage.ObjectStat override = statOverrides.get(objectKey);
        if (override != null) {
            return override;
        }
        byte[] content = objects.get(objectKey);
        if (content == null) {
            throw new IllegalStateException("Synthetic object was not uploaded");
        }
        return new PrivateObjectStorage.ObjectStat(md5Etag(content), content.length, "application/pdf");
    }

    private Map<String, Object> fileRow(long fileId) {
        return jdbcTemplate.queryForMap("SELECT * FROM crm_file_object WHERE tenant_id=? AND id=?",
                TENANT_ID, fileId);
    }

    private String scanStatus(long fileId) {
        return jdbcTemplate.queryForObject("SELECT scan_status FROM crm_file_object WHERE tenant_id=? AND id=?",
                String.class, TENANT_ID, fileId);
    }

    private long countFilesNamed(String originalName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_file_object WHERE tenant_id=? AND original_name=?",
                Long.class, TENANT_ID, originalName);
    }

    private long countContracts(long fileId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_institution_contract WHERE tenant_id=? AND file_id=?",
                Long.class, TENANT_ID, fileId);
    }

    private long countDownloadAudits(long fileId, long actorUserId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=? "
                        + "AND action_code='FILE_DOWNLOAD' AND target_type='FILE' AND target_id=? AND actor_user_id=?",
                Long.class, TENANT_ID, Long.toString(fileId), actorUserId);
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID() + ".pdf";
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String md5Etag(byte[] content) throws Exception {
        return '"' + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content)) + '"';
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private DataResult data(MockHttpServletRequestBuilder request) throws Exception {
        var result = mockMvc.perform(request);
        return new DataResult(result);
    }

    private record Upload(long id, String objectKey, String sha256, String uploadUrl, Instant expiresAt) {
    }

    private class DataResult {
        private final org.springframework.test.web.servlet.ResultActions actions;

        private DataResult(org.springframework.test.web.servlet.ResultActions actions) {
            this.actions = actions;
        }

        private DataResult andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        private JsonNode andReturnData() throws Exception {
            String response = actions.andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(response).at("/data");
        }
    }
}
