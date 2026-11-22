package com.whale.crm.file;

import com.whale.crm.file.api.dto.UploadCompleteRequest;
import com.whale.crm.file.api.dto.UploadInitRequest;
import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.application.FileContentInspector;
import com.whale.crm.file.application.FileScopeService;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.file.domain.FileRepository;
import com.whale.crm.file.infrastructure.FileProperties;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FileP2BoundaryTest {
    private InstitutionAccessSupport institutionAccess;
    private DataScopeService dataScopeService;
    private FileScopeService scopeService;
    private FileRepository repository;
    private PrivateObjectStorage storage;
    private ClamAvScanner scanner;
    private FileProperties properties;
    private FileApplicationService fileService;

    @BeforeEach
    void setUp() {
        institutionAccess = mock(InstitutionAccessSupport.class);
        dataScopeService = mock(DataScopeService.class);
        scopeService = new FileScopeService(institutionAccess, dataScopeService);
        repository = mock(FileRepository.class);
        storage = mock(PrivateObjectStorage.class);
        scanner = mock(ClamAvScanner.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivateObjectStorage> storageProvider = mock(ObjectProvider.class);
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        properties = new FileProperties();
        properties.setEnabled(true);
        properties.setMaxFileSizeBytes(1024L);
        fileService = new FileApplicationService(repository, storageProvider, mock(FileContentInspector.class),
                scanner, scopeService, properties, mock(AuditService.class),
                mock(PlatformTransactionManager.class));
    }

    @Test
    void paymentRequiresFinancialPermissionAndAllCustomerScope() {
        CurrentUser manager = employee("order:read");
        assertBusinessError(() -> scopeService.requireDownload(manager, FileClassification.PAYMENT, null),
                ErrorCode.FORBIDDEN);

        CurrentUser finance = employee("order:read", "order:confirm_payment");
        for (DataScope scope : java.util.List.of(DataScope.self(), DataScope.team(Set.of(1L)), DataScope.none())) {
            when(dataScopeService.scopeFor(finance, DataScopeService.RESOURCE_CUSTOMER)).thenReturn(scope);
            assertBusinessError(() -> scopeService.requireDownload(finance, FileClassification.PAYMENT, null),
                    ErrorCode.NOT_FOUND);
            assertBusinessError(() -> scopeService.requireUpload(finance, FileClassification.PAYMENT, null),
                    ErrorCode.FORBIDDEN);
        }
        when(dataScopeService.scopeFor(finance, DataScopeService.RESOURCE_CUSTOMER)).thenReturn(DataScope.all());
        assertThatCode(() -> scopeService.requireDownload(finance, FileClassification.PAYMENT, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> scopeService.requireUpload(finance, FileClassification.PAYMENT, null))
                .doesNotThrowAnyException();
        CurrentUser institution = new CurrentUser(10, 1, "operator", "合成操作员", "INSTITUTION", null,
                Set.of("INSTITUTION_STAFF"), Set.of("order:read", "order:confirm_payment"), Set.of(1L));
        assertBusinessError(() -> scopeService.requireDownload(institution, FileClassification.PAYMENT, null),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void globalMaterialRequiresExplicitAllScope() {
        CurrentUser user = employee("material:read");
        when(dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION)).thenReturn(DataScope.all());

        assertThatCode(() -> scopeService.requireDownload(user, FileClassification.MATERIAL, null))
                .doesNotThrowAnyException();

        verify(dataScopeService).scopeFor(user, DataScopeService.RESOURCE_INSTITUTION);
    }

    @Test
    void globalMaterialDoesNotBypassTeamScope() {
        CurrentUser user = employee("material:read");
        when(dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION))
                .thenReturn(DataScope.team(Set.of(1L)));

        assertBusinessError(() -> scopeService.requireDownload(user, FileClassification.MATERIAL, null),
                ErrorCode.NOT_FOUND);

        verify(dataScopeService).scopeFor(user, DataScopeService.RESOURCE_INSTITUTION);
    }

    @Test
    void institutionAccountCannotUseNullInstitutionForGlobalMaterial() {
        CurrentUser user = new CurrentUser(10, 1, "operator", "合成操作员", "INSTITUTION", null,
                Set.of("INSTITUTION_STAFF"), Set.of("material:read"), Set.of(1L));

        assertBusinessError(() -> scopeService.requireDownload(user, FileClassification.MATERIAL, null),
                ErrorCode.NOT_FOUND);

        verifyNoInteractions(dataScopeService, institutionAccess);
    }

    @Test
    void exportScopeUsesExportPermissionInsteadOfInstitutionScope() {
        CurrentUser user = employee("export:read");

        assertThatCode(() -> scopeService.requireDownload(user, FileClassification.valueOf("EXPORT"), null))
                .doesNotThrowAnyException();
        assertBusinessError(() -> scopeService.requireDownload(employee("order:read"),
                FileClassification.valueOf("EXPORT"), null), ErrorCode.FORBIDDEN);

        verifyNoInteractions(dataScopeService, institutionAccess);
    }

    @Test
    void genericDownloadNeverIssuesMaterialUrlWithoutRevisionReview() {
        CurrentUser user = employee("material:read");
        when(repository.findById(1, 41)).thenReturn(Optional.of(metadata(41, "MATERIAL", "CLEAN")));

        assertBusinessError(() -> fileService.downloadGeneric(1, user, 41), ErrorCode.FORBIDDEN);

        verify(storage, never()).presignGet(anyString(), anyInt());
    }

    @Test
    void genericDownloadNeverIssuesExportUrl() {
        CurrentUser user = employee("export:read");
        when(repository.findById(1, 42)).thenReturn(Optional.of(metadata(42, "EXPORT", "CLEAN")));

        assertBusinessError(() -> fileService.downloadGeneric(1, user, 42), ErrorCode.FORBIDDEN);

        verify(storage, never()).presignGet(anyString(), anyInt());
    }

    @Test
    void exportUploadIsNeverAcceptedThroughClientFileScope() {
        CurrentUser user = employee("export:create");

        assertBusinessError(() -> scopeService.requireUpload(user, FileClassification.EXPORT, null),
                ErrorCode.FORBIDDEN);

        verifyNoInteractions(dataScopeService, institutionAccess);
    }

    @Test
    void institutionAccountCannotDownloadExportEvenWithPermission() {
        CurrentUser user = new CurrentUser(10, 1, "operator", "合成操作员", "INSTITUTION", null,
                Set.of("INSTITUTION_STAFF"), Set.of("export:read"), Set.of(1L));

        assertBusinessError(() -> scopeService.requireDownload(user, FileClassification.EXPORT, null),
                ErrorCode.NOT_FOUND);

        verifyNoInteractions(dataScopeService, institutionAccess);
    }

    @Test
    void internalMaterialDownloadRetainsCleanAndGlobalScopeChecks() {
        CurrentUser user = employee("material:read");
        when(dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION)).thenReturn(DataScope.all());
        when(repository.findById(1, 44)).thenReturn(Optional.of(metadata(44, "MATERIAL", "CLEAN")));

        assertThatCode(() -> fileService.download(1, user, 44)).doesNotThrowAnyException();

        verify(storage).presignGet(org.mockito.ArgumentMatchers.eq("sealed/1/44"), anyInt());
    }

    @Test
    void materialCandidateMustBeCleanBeforeItCanBeReferenced() {
        CurrentUser user = employee("material:read");
        when(repository.findById(1, 43)).thenReturn(Optional.of(metadata(43, "MATERIAL", "PENDING")));

        assertBusinessError(() -> fileService.requireCleanUsable(1, user, 43, FileClassification.MATERIAL, null),
                ErrorCode.CONFLICT);
    }

    @Test
    void globalMaterialCannotReferenceAnInstitutionFile() {
        CurrentUser user = employee("material:read");
        when(repository.findById(1, 45)).thenReturn(Optional.of(metadata(45, "MATERIAL", "CLEAN", 1L)));

        assertBusinessError(() -> fileService.requireCleanUsable(1, user, 45, FileClassification.MATERIAL, null),
                ErrorCode.FORBIDDEN);

        verifyNoInteractions(dataScopeService, institutionAccess);
    }

    @Test
    void uploadInitRejectsTextMimeForNonMaterialNonPdfClassifications() {
        CurrentUser finance = employee("order:confirm_payment", "redemption:post", "institution:write");
        when(dataScopeService.scopeFor(finance, DataScopeService.RESOURCE_CUSTOMER)).thenReturn(DataScope.all());
        when(dataScopeService.scopeFor(finance, DataScopeService.RESOURCE_INSTITUTION)).thenReturn(DataScope.all());

        for (String classification : java.util.List.of("PAYMENT", "REDEMPTION", "MERCHANT_RESOURCE")) {
            Long institutionId = "PAYMENT".equals(classification) ? null : 1L;
            for (String mime : java.util.List.of("text/plain", "text/markdown", "text/x-markdown")) {
                UploadInitRequest request = new UploadInitRequest("note.txt", mime, 16L,
                        "a".repeat(64), classification, institutionId);
                assertBusinessError(() -> fileService.uploadInit(1L, finance, request), ErrorCode.VALIDATION_FAILED);
            }
        }
        verify(storage, never()).presignPut(anyString(), anyInt());
    }

    @Test
    void uploadCompleteRejectsOversizedStatBeforeGetObjectAndDeletesStagingObject() {
        CurrentUser user = employee("institution:write");
        when(dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION)).thenReturn(DataScope.all());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        FileMetadata pending = new FileMetadata(51L, 1L, "contract/1/oversize.pdf", "oversize.pdf",
                "application/pdf", 512L, "a".repeat(64), "CONTRACT", 1L, "PENDING",
                2L, null, null, null, now, now, 0L);
        when(repository.findById(1L, 51L)).thenReturn(Optional.of(pending));
        when(repository.startCompletion(1L, 51L, 0L)).thenReturn(1);
        when(storage.stat("contract/1/oversize.pdf"))
                .thenReturn(new PrivateObjectStorage.ObjectStat("b".repeat(32), 2048L, "application/pdf"));

        assertBusinessError(() -> fileService.uploadComplete(1L, user, new UploadCompleteRequest(51L, "b".repeat(32))),
                ErrorCode.CONFLICT);

        verify(storage, never()).getObject(anyString());
        verify(scanner, never()).scan(any());
        verify(storage).deleteObject("contract/1/oversize.pdf");
    }

    @Test
    void uploadCompleteAbortsStreamingOnExcessByteWithoutReadingWholeStream() {
        CurrentUser user = employee("institution:write");
        when(dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION)).thenReturn(DataScope.all());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        FileMetadata pending = new FileMetadata(52L, 1L, "contract/1/lying-stream.pdf", "lying.pdf",
                "application/pdf", 512L, "a".repeat(64), "CONTRACT", 1L, "PENDING",
                2L, null, null, null, now, now, 0L);
        when(repository.findById(1L, 52L)).thenReturn(Optional.of(pending));
        when(repository.startCompletion(1L, 52L, 0L)).thenReturn(1);
        when(storage.stat("contract/1/lying-stream.pdf"))
                .thenReturn(new PrivateObjectStorage.ObjectStat("b".repeat(32), 512L, "application/pdf"));
        AtomicInteger chunksRead = new AtomicInteger();
        InputStream lyingStream = new InputStream() {
            @Override
            public int read() {
                return 'x';
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (chunksRead.incrementAndGet() > 5) {
                    throw new AssertionError("Stream was not aborted early");
                }
                java.util.Arrays.fill(b, off, off + Math.min(len, 400), (byte) 'x');
                return Math.min(len, 400);
            }
        };
        when(storage.getObject("contract/1/lying-stream.pdf")).thenReturn(lyingStream);

        assertBusinessError(() -> fileService.uploadComplete(1L, user, new UploadCompleteRequest(52L, "b".repeat(32))),
                ErrorCode.CONFLICT);

        assertThat(chunksRead.get()).isEqualTo(2);
        verify(scanner, never()).scan(any());
        verify(storage).deleteObject("contract/1/lying-stream.pdf");
    }

    @Test
    void ensureSealedRejectsOversizedStatAndHandlesConcurrentSealRaceGracefully() {
        CurrentUser user = employee("institution:read");
        when(dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION)).thenReturn(DataScope.all());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        FileMetadata oversizedLegacy = new FileMetadata(61L, 1L, "contract/1/legacy-big.pdf", "big.pdf",
                "application/pdf", 512L, "a".repeat(64), "CONTRACT", 1L, "CLEAN",
                2L, "b".repeat(32), null, null, now, now, 1L);
        when(repository.findById(1L, 61L)).thenReturn(Optional.of(oversizedLegacy));
        when(storage.stat("contract/1/legacy-big.pdf"))
                .thenReturn(new PrivateObjectStorage.ObjectStat("b".repeat(32), 2048L, "application/pdf"));

        assertBusinessError(() -> fileService.requireCleanUsable(1L, user, 61L, FileClassification.CONTRACT, 1L),
                ErrorCode.CONFLICT);
        verify(storage, never()).getObject("contract/1/legacy-big.pdf");
        verify(storage).deleteObject("contract/1/legacy-big.pdf");

        FileMetadata racedLegacy = new FileMetadata(62L, 1L, "contract/1/legacy-race.pdf", "race.pdf",
                "application/pdf", 16L, "a".repeat(64), "CONTRACT", 1L, "CLEAN",
                2L, "b".repeat(32), null, null, now, now, 1L);
        FileMetadata alreadySealed = new FileMetadata(62L, 1L, "sealed/1/winner-uuid", "race.pdf",
                "application/pdf", 16L, "a".repeat(64), "CONTRACT", 1L, "CLEAN",
                2L, "b".repeat(32), null, null, now, now, 2L);
        when(repository.findById(1L, 62L)).thenReturn(Optional.of(racedLegacy), Optional.of(alreadySealed));
        when(storage.stat("contract/1/legacy-race.pdf")).thenThrow(new IllegalStateException("Object already deleted"));

        FileMetadata returned = fileService.requireCleanUsable(1L, user, 62L, FileClassification.CONTRACT, 1L);
        assertThat(returned.objectKey()).isEqualTo("sealed/1/winner-uuid");
        verify(storage, never()).deleteObject("contract/1/legacy-race.pdf");
    }

    @Test
    void copyBoundedAlsoRejectsShortStreamBeforeInspection() {
        CurrentUser user = employee("institution:write");
        when(dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION)).thenReturn(DataScope.all());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        FileMetadata pending = new FileMetadata(53L, 1L, "contract/1/short-stream.pdf", "short.pdf",
                "application/pdf", 512L, "a".repeat(64), "CONTRACT", 1L, "PENDING",
                2L, null, null, null, now, now, 0L);
        when(repository.findById(1L, 53L)).thenReturn(Optional.of(pending));
        when(repository.startCompletion(1L, 53L, 0L)).thenReturn(1);
        when(storage.stat("contract/1/short-stream.pdf"))
                .thenReturn(new PrivateObjectStorage.ObjectStat("b".repeat(32), 512L, "application/pdf"));
        when(storage.getObject("contract/1/short-stream.pdf")).thenReturn(new ByteArrayInputStream(new byte[100]));
        when(repository.updateScanStatus(1L, 53L, 1L, "ERROR", "SIZE_MISMATCH")).thenReturn(1);

        assertBusinessError(() -> fileService.uploadComplete(1L, user, new UploadCompleteRequest(53L, "b".repeat(32))),
                ErrorCode.CONFLICT);

        verify(scanner, never()).scan(any());
        verify(storage).deleteObject("contract/1/short-stream.pdf");
        verify(repository).updateScanStatus(1L, 53L, 1L, "ERROR", "SIZE_MISMATCH");
    }

    private CurrentUser employee(String... permissions) {
        return new CurrentUser(2, 1, "manager", "合成管家", "EMPLOYEE", 1L,
                Set.of("MANAGER"), Set.of(permissions), Set.of());
    }

    private FileMetadata metadata(long id, String classification, String scanStatus) {
        return metadata(id, classification, scanStatus, null);
    }

    private FileMetadata metadata(long id, String classification, String scanStatus, Long institutionId) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new FileMetadata(id, 1L, "sealed/1/" + id, "synthetic.dat",
                "application/octet-stream", 1L, "a".repeat(64), classification, institutionId, scanStatus,
                2L, null, null, null, now, now, 3L);
    }

    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(expected);
    }
}
