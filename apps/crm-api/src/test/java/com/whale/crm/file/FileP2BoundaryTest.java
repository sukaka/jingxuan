package com.whale.crm.file;

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

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private FileApplicationService fileService;

    @BeforeEach
    void setUp() {
        institutionAccess = mock(InstitutionAccessSupport.class);
        dataScopeService = mock(DataScopeService.class);
        scopeService = new FileScopeService(institutionAccess, dataScopeService);
        repository = mock(FileRepository.class);
        storage = mock(PrivateObjectStorage.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivateObjectStorage> storageProvider = mock(ObjectProvider.class);
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        FileProperties properties = new FileProperties();
        properties.setEnabled(true);
        fileService = new FileApplicationService(repository, storageProvider, mock(FileContentInspector.class),
                mock(ClamAvScanner.class), scopeService, properties, mock(AuditService.class),
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
