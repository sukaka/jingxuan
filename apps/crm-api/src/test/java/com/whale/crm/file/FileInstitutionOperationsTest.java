package com.whale.crm.file;

import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.application.FileContentInspector;
import com.whale.crm.file.application.FileInstitutionOperations;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FileInstitutionOperationsTest {
    private CurrentUser currentUser;
    private FileRepository repository;
    private DataScopeService dataScopeService;
    private FileApplicationService realFileService;

    @BeforeEach
    void setUp() {
        currentUser = new CurrentUser(2L, 1L, "business1", "合成商务", "EMPLOYEE", 1L,
                Set.of("BUSINESS"), Set.of("institution:read", "institution:write"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null, List.of()));
        repository = mock(FileRepository.class);
        dataScopeService = mock(DataScopeService.class);
        when(dataScopeService.scopeFor(currentUser, DataScopeService.RESOURCE_INSTITUTION))
                .thenReturn(DataScope.all());
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivateObjectStorage> storageProvider = mock(ObjectProvider.class);
        when(storageProvider.getIfAvailable()).thenReturn(mock(PrivateObjectStorage.class));
        FileProperties properties = new FileProperties();
        properties.setEnabled(true);
        FileScopeService scopeService = new FileScopeService(mock(InstitutionAccessSupport.class), dataScopeService);
        realFileService = new FileApplicationService(repository, storageProvider, mock(FileContentInspector.class),
                mock(ClamAvScanner.class), scopeService, properties, mock(AuditService.class),
                mock(PlatformTransactionManager.class));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requireCleanValidatesInstitutionIdAndAllowedClassificationsBeforeDelegating() {
        FileApplicationService mockedService = mock(FileApplicationService.class);
        FileInstitutionOperations operations = new FileInstitutionOperations(mockedService);

        assertBusinessError(() -> operations.requireClean(1L, 0L, 10L, FileClassification.CONTRACT),
                ErrorCode.VALIDATION_FAILED);
        assertBusinessError(() -> operations.requireClean(1L, -5L, 10L, FileClassification.CONTRACT),
                ErrorCode.VALIDATION_FAILED);
        assertBusinessError(() -> operations.requireClean(1L, 1L, 10L, null),
                ErrorCode.CONFLICT);
        for (FileClassification disallowed : List.of(FileClassification.PAYMENT, FileClassification.REDEMPTION,
                FileClassification.MATERIAL, FileClassification.EXPORT)) {
            assertBusinessError(() -> operations.requireClean(1L, 1L, 10L, disallowed), ErrorCode.CONFLICT);
        }
        verifyNoInteractions(mockedService);

        for (FileClassification allowed : List.of(FileClassification.CONTRACT, FileClassification.QUALIFICATION,
                FileClassification.CHANGE_EVIDENCE, FileClassification.MERCHANT_RESOURCE)) {
            operations.requireClean(1L, 7L, 10L, allowed);
            verify(mockedService).requireCleanUsable(1L, currentUser, 10L, allowed, 7L);
        }
    }

    @Test
    void requireCleanEnforcesInstitutionOwnershipClassificationAndScanStatusViaFileService() {
        FileInstitutionOperations operations = new FileInstitutionOperations(realFileService);
        when(repository.findById(1L, 100L)).thenReturn(Optional.of(metadata(100L, "CONTRACT", 1L, "CLEAN")));
        when(repository.findById(1L, 101L)).thenReturn(Optional.of(metadata(101L, "CONTRACT", 2L, "CLEAN")));
        when(repository.findById(1L, 102L)).thenReturn(Optional.of(metadata(102L, "CONTRACT", null, "CLEAN")));
        when(repository.findById(1L, 103L)).thenReturn(Optional.of(metadata(103L, "CHANGE_EVIDENCE", 1L, "CLEAN")));
        when(repository.findById(1L, 104L)).thenReturn(Optional.of(metadata(104L, "CONTRACT", 1L, "PENDING")));

        assertThatCode(() -> operations.requireClean(1L, 1L, 100L, FileClassification.CONTRACT))
                .doesNotThrowAnyException();
        assertBusinessError(() -> operations.requireClean(1L, 1L, 101L, FileClassification.CONTRACT),
                ErrorCode.FORBIDDEN);
        assertBusinessError(() -> operations.requireClean(1L, 1L, 102L, FileClassification.CONTRACT),
                ErrorCode.FORBIDDEN);
        assertBusinessError(() -> operations.requireClean(1L, 1L, 103L, FileClassification.CONTRACT),
                ErrorCode.CONFLICT);
        assertBusinessError(() -> operations.requireClean(1L, 1L, 104L, FileClassification.CONTRACT),
                ErrorCode.CONFLICT);
    }

    private FileMetadata metadata(long id, String classification, Long institutionId, String scanStatus) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new FileMetadata(id, 1L, "sealed/1/" + id, "doc.pdf", "application/pdf", 128L,
                "a".repeat(64), classification, institutionId, scanStatus, 2L, null, null, null, now, now, 1L);
    }

    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(expected);
    }
}
