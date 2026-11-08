package com.whale.crm.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.file.api.dto.DownloadResponse;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.material.api.dto.CreateMaterialRevisionRequest;
import com.whale.crm.material.api.dto.CreateMaterialRequest;
import com.whale.crm.material.api.dto.MaterialDownloadResponse;
import com.whale.crm.material.api.dto.MaterialResponse;
import com.whale.crm.material.api.dto.OfflineMaterialRequest;
import com.whale.crm.material.api.dto.ReviewMaterialRevisionRequest;
import com.whale.crm.material.api.dto.SubmitMaterialRevisionRequest;
import com.whale.crm.material.api.dto.UpdateMaterialRequest;
import com.whale.crm.material.application.MaterialApplicationService;
import com.whale.crm.material.application.MaterialScopeService;
import com.whale.crm.material.domain.Material;
import com.whale.crm.material.domain.MaterialListRow;
import com.whale.crm.material.domain.MaterialRepository;
import com.whale.crm.material.domain.MaterialRevision;
import com.whale.crm.material.domain.MaterialScopeQuery;
import com.whale.crm.material.domain.MaterialSearchCriteria;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaterialApplicationServiceTest {
    private static final long TENANT = 1L;
    private static final CurrentUser WRITER = user(7, "EMPLOYEE", Set.of("material:read", "material:write"), Set.of());
    private static final CurrentUser REVIEWER = user(8, "EMPLOYEE", Set.of("material:review"), Set.of());
    private static final CurrentUser READER = user(5, "EMPLOYEE", Set.of("material:read"), Set.of());
    private static final CurrentUser APPLICANT_REVIEWER = user(7, "EMPLOYEE",
            Set.of("material:read", "material:write", "material:review"), Set.of());

    private MaterialRepository repository;
    private DataScopeService dataScopeService;
    private FileApplicationService fileService;
    private AuditService auditService;
    private IdempotencyService idempotencyService;
    private MaterialApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(MaterialRepository.class);
        dataScopeService = mock(DataScopeService.class);
        fileService = mock(FileApplicationService.class);
        auditService = mock(AuditService.class);
        idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.lookup(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(dataScopeService.scopeFor(any(), eq(DataScopeService.RESOURCE_INSTITUTION)))
                .thenReturn(DataScope.all());
        service = new MaterialApplicationService(repository, new MaterialScopeService(dataScopeService),
                fileService, auditService, idempotencyService, new ObjectMapper());
    }

    @Test
    void createsMaterialAndFirstRevisionAsDraftOnlyWithCleanFile() {
        Material material = material("DRAFT", null);
        MaterialRevision revision = revision(11, 1, "DRAFT", 7);
        when(fileService.requireCleanUsable(TENANT, WRITER, 99, FileClassification.MATERIAL, 1L))
                .thenReturn(cleanFile(1L));
        when(repository.findInsertedMaterial(eq(TENANT), anyString(), eq(7L))).thenReturn(Optional.of(material));
        when(repository.findInsertedRevision(TENANT, 10, 1, 7)).thenReturn(Optional.of(revision));
        stubDetail(material, List.of(revision));

        MaterialResponse response = service.create(TENANT, WRITER, createRequest(1L));

        assertThat(response.reviewStatus()).isEqualTo("DRAFT");
        assertThat(response.revisions()).hasSize(1);
        ArgumentCaptor<Material> materialCaptor = ArgumentCaptor.forClass(Material.class);
        verify(repository).insertMaterial(materialCaptor.capture());
        assertThat(materialCaptor.getValue().materialNo()).startsWith("MT");
        assertThat(materialCaptor.getValue().currentRevisionId()).isNull();
        verify(fileService).requireCleanUsable(TENANT, WRITER, 99, FileClassification.MATERIAL, 1L);
        verify(auditService).record(eq(TENANT), eq(7L), eq("MATERIAL_CREATE"), eq("MATERIAL"),
                eq("10"), any(), any(), any());
    }

    @Test
    void approvedRevisionCannotBeOverwritten() {
        Material material = material("APPROVED", 11L);
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any())).thenReturn(Optional.of(material));
        when(repository.findScopedRevisionForUpdate(eq(TENANT), eq(10L), eq(11L), any())).thenReturn(Optional.of(revision(11, 1, "APPROVED", 7)));

        assertThatThrownBy(() -> service.update(TENANT, WRITER, 10, updateRequest("APPROVED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("草稿");

        verify(repository, never()).updateDraft(any(), any());
    }

    @Test
    void updatesDraftWithOptimisticLockAndAudit() {
        Material draft = material("DRAFT", null);
        MaterialRevision draftRevision = revision(11, 1, "DRAFT", 7);
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any())).thenReturn(Optional.of(draft));
        when(repository.findScopedRevisionForUpdate(eq(TENANT), eq(10L), eq(11L), any()))
                .thenReturn(Optional.of(draftRevision));
        when(fileService.requireCleanUsable(TENANT, WRITER, 99, FileClassification.MATERIAL, 1L))
                .thenReturn(cleanFile(1L));
        when(repository.updateDraft(any(), any())).thenReturn(1);
        when(repository.updateMaterialContent(eq(TENANT), eq(10L), eq(3L), any(), any())).thenReturn(1);
        stubDetail(draft, List.of(draftRevision));

        MaterialResponse response = service.update(TENANT, WRITER, 10, updateRequest("DRAFT"));

        assertThat(response.reviewStatus()).isEqualTo("DRAFT");
        ArgumentCaptor<MaterialRevision> captor = ArgumentCaptor.forClass(MaterialRevision.class);
        verify(repository).updateDraft(captor.capture(), any());
        assertThat(captor.getValue().title()).isEqualTo("合成素材修改");
        assertThat(captor.getValue().version()).isEqualTo(2L);
        verify(repository).updateMaterialContent(eq(TENANT), eq(10L), eq(3L), any(), any());
        verify(auditService).record(eq(TENANT), eq(7L), eq("MATERIAL_UPDATE"), eq("MATERIAL"),
                eq("10"), any(), any(), any());
    }

    @Test
    void newRevisionStartsDraftAndKeepsCurrentApprovedRevision() {
        Material approved = material("APPROVED", 11L);
        MaterialRevision approvedRevision = revision(11, 1, "APPROVED", 7);
        MaterialRevision newDraft = revision(12, 2, "DRAFT", 7);
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any())).thenReturn(Optional.of(approved));
        when(repository.findLatestScopedRevisionForUpdate(eq(TENANT), eq(10L), any())).thenReturn(Optional.of(approvedRevision));
        when(fileService.requireCleanUsable(TENANT, WRITER, 99, FileClassification.MATERIAL, 1L))
                .thenReturn(cleanFile(1L));
        when(repository.findInsertedRevision(TENANT, 10, 2, 7)).thenReturn(Optional.of(newDraft));
        when(repository.updateMaterialContent(eq(TENANT), eq(10L), eq(3L), any(), any())).thenReturn(1);
        stubDetail(material("DRAFT", 11L), List.of(newDraft, approvedRevision));

        MaterialResponse response = service.createRevision(TENANT, WRITER, 10, revisionRequest());

        assertThat(response.currentRevisionId()).isEqualTo(11L);
        ArgumentCaptor<MaterialRevision> captor = ArgumentCaptor.forClass(MaterialRevision.class);
        verify(repository).insertRevision(captor.capture());
        assertThat(captor.getValue().revisionNo()).isEqualTo(2);
        assertThat(captor.getValue().status()).isEqualTo("DRAFT");
        verify(repository).updateMaterialContent(eq(TENANT), eq(10L), eq(3L), any(), any());
    }

    @Test
    void submitsOnlyDraftAndRechecksCleanStatus() {
        Material draft = material("DRAFT", null);
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any())).thenReturn(Optional.of(draft));
        when(repository.findScopedRevisionForUpdate(eq(TENANT), eq(10L), eq(11L), any())).thenReturn(Optional.of(revision(11, 1, "DRAFT", 7)));
        when(fileService.requireCleanUsable(TENANT, WRITER, 99, FileClassification.MATERIAL, 1L))
                .thenReturn(cleanFile(1L));
        when(repository.submitRevision(eq(TENANT), eq(11L), eq(10L), eq(2L), any())).thenReturn(1);
        when(repository.submitMaterial(eq(TENANT), eq(10L), eq(3L), any())).thenReturn(1);
        stubDetail(material("PENDING", null), List.of(revision(11, 1, "PENDING", 7)));

        MaterialResponse response = service.submit(TENANT, WRITER, 10, 11, new SubmitMaterialRevisionRequest(2L));

        assertThat(response.reviewStatus()).isEqualTo("PENDING");
        verify(repository).submitRevision(eq(TENANT), eq(11L), eq(10L), eq(2L), any());
        verify(repository).submitMaterial(eq(TENANT), eq(10L), eq(3L), any());
    }

    @Test
    void uncleanFileCannotBeSubmitted() {
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any())).thenReturn(Optional.of(material("DRAFT", null)));
        when(repository.findScopedRevisionForUpdate(eq(TENANT), eq(10L), eq(11L), any()))
                .thenReturn(Optional.of(revision(11, 1, "DRAFT", 7)));
        when(fileService.requireCleanUsable(TENANT, WRITER, 99, FileClassification.MATERIAL, 1L))
                .thenThrow(new BusinessException(com.whale.crm.platform.web.ErrorCode.CONFLICT, "文件尚未通过安全扫描"));

        assertThatThrownBy(() -> service.submit(TENANT, WRITER, 10, 11, new SubmitMaterialRevisionRequest(2L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("安全扫描");

        verify(repository, never()).submitRevision(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(repository, never()).submitMaterial(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void applicantCannotReviewOwnSubmissionEvenWithReviewPermission() {
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any()))
                .thenReturn(Optional.of(material("PENDING", null)));
        when(repository.findScopedRevisionForUpdate(eq(TENANT), eq(10L), eq(11L), any()))
                .thenReturn(Optional.of(revision(11, 1, "PENDING", 7)));

        assertThatThrownBy(() -> service.review(TENANT, APPLICANT_REVIEWER, 10, 11,
                new ReviewMaterialRevisionRequest(2L, "APPROVED", "合成审核意见")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("自审");

        verify(repository, never()).reviewRevision(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any(), any(), any());
        verify(fileService, never()).requireCleanUsable(anyLong(), any(), anyLong(), any(FileClassification.class), any());
    }

    @Test
    void reviewerApprovesAnotherUsersPendingRevision() {
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any()))
                .thenReturn(Optional.of(material("PENDING", null)));
        when(repository.findScopedRevisionForUpdate(eq(TENANT), eq(10L), eq(11L), any()))
                .thenReturn(Optional.of(revision(11, 1, "PENDING", 7)));
        when(fileService.requireCleanUsable(TENANT, REVIEWER, 99, FileClassification.MATERIAL, 1L))
                .thenReturn(cleanFile(1L));
        when(repository.reviewRevision(eq(TENANT), eq(11L), eq(10L), eq(2L), eq("APPROVED"), eq(8L), eq("通过"), any(), any()))
                .thenReturn(1);
        when(repository.reviewMaterial(eq(TENANT), eq(10L), eq(3L), eq("APPROVED"), eq(8L), eq(11L), any()))
                .thenReturn(1);
        stubDetail(material("APPROVED", 11L), List.of(revision(11, 1, "APPROVED", 7)));

        MaterialResponse response = service.review(TENANT, REVIEWER, 10, 11,
                new ReviewMaterialRevisionRequest(2L, "APPROVED", "通过"));

        assertThat(response.reviewStatus()).isEqualTo("APPROVED");
        verify(repository).reviewMaterial(eq(TENANT), eq(10L), eq(3L), eq("APPROVED"), eq(8L), eq(11L), any());
        verify(auditService).record(eq(TENANT), eq(8L), eq("MATERIAL_REVIEW"), eq("MATERIAL"),
                eq("10"), any(), any(), eq("通过"));
    }

    @Test
    void rejectionKeepsPreviousApprovedRevisionCurrent() {
        MaterialRevision pending = revision(11, 2, "PENDING", 7);
        MaterialRevision previousApproved = revision(10, 1, "APPROVED", 7);
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any()))
                .thenReturn(Optional.of(material("PENDING", 10L)));
        when(repository.findScopedRevisionForUpdate(eq(TENANT), eq(10L), eq(11L), any()))
                .thenReturn(Optional.of(pending));
        when(fileService.requireCleanUsable(TENANT, REVIEWER, 99, FileClassification.MATERIAL, 1L))
                .thenReturn(cleanFile(1L));
        when(repository.reviewRevision(eq(TENANT), eq(11L), eq(10L), eq(2L), eq("REJECTED"), eq(8L), eq("不适用"), any(), any()))
                .thenReturn(1);
        when(repository.reviewMaterial(eq(TENANT), eq(10L), eq(3L), eq("REJECTED"), eq(8L),
                org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        stubDetail(material("REJECTED", 10L), List.of(revision(11, 2, "REJECTED", 7), previousApproved));

        MaterialResponse response = service.review(TENANT, REVIEWER, 10, 11,
                new ReviewMaterialRevisionRequest(2L, "REJECTED", "不适用"));

        assertThat(response.reviewStatus()).isEqualTo("REJECTED");
        assertThat(response.currentRevisionId()).isEqualTo(10L);
        verify(repository).reviewMaterial(eq(TENANT), eq(10L), eq(3L), eq("REJECTED"), eq(8L),
                org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void reviewerCanPreviewPendingRevisionAndDownloadIsAuditedWithoutPersistingUrl() {
        MaterialRevision pending = revision(11, 1, "PENDING", 7);
        when(repository.findScopedRevision(eq(TENANT), eq(10L), eq(11L), any())).thenReturn(Optional.of(pending));
        when(fileService.requireCleanUsable(TENANT, REVIEWER, 99, FileClassification.MATERIAL, 1L))
                .thenReturn(cleanFile(1L));
        Instant expiresAt = Instant.parse("2026-10-27T08:02:00Z");
        when(fileService.download(TENANT, REVIEWER, 99))
                .thenReturn(new DownloadResponse(99L, "https://private.invalid/signed", expiresAt));

        MaterialDownloadResponse response = service.download(TENANT, REVIEWER, 10, 11);

        assertThat(response.downloadUrl()).isEqualTo("https://private.invalid/signed");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        ArgumentCaptor<Object> auditAfter = ArgumentCaptor.forClass(Object.class);
        verify(auditService).record(eq(TENANT), eq(8L), eq("MATERIAL_DOWNLOAD"), eq("MATERIAL_REVISION"),
                eq("11"), any(), auditAfter.capture(), eq("PENDING"));
        assertThat(auditAfter.getValue()).isInstanceOf(Map.class);
        assertThat(auditAfter.getValue().toString()).doesNotContain("signed", "downloadUrl");
    }

    @Test
    void ordinaryReaderCannotPreviewPendingRevision() {
        when(repository.findScopedRevision(eq(TENANT), eq(10L), eq(11L), any()))
                .thenReturn(Optional.of(revision(11, 1, "PENDING", 7)));

        assertThatThrownBy(() -> service.download(TENANT, READER, 10, 11))
                .isInstanceOf(BusinessException.class);

        verify(fileService, never()).download(anyLong(), any(), anyLong());
    }

    @Test
    void uncleanApprovedRevisionCannotBeDownloaded() {
        when(repository.findScopedRevision(eq(TENANT), eq(10L), eq(11L), any()))
                .thenReturn(Optional.of(revision(11, 1, "APPROVED", 7)));
        when(fileService.requireCleanUsable(TENANT, READER, 99, FileClassification.MATERIAL, 1L))
                .thenThrow(new BusinessException(com.whale.crm.platform.web.ErrorCode.CONFLICT, "文件尚未通过安全扫描"));

        assertThatThrownBy(() -> service.download(TENANT, READER, 10, 11))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("安全扫描");

        verify(fileService, never()).download(anyLong(), any(), anyLong());
        verify(auditService, never()).record(anyLong(), any(), eq("MATERIAL_DOWNLOAD"), any(), any(), any(), any(), any());
    }

    @Test
    void globalApprovedRevisionStillRequiresAllScopeForDownload() {
        when(dataScopeService.scopeFor(READER, DataScopeService.RESOURCE_INSTITUTION))
                .thenReturn(DataScope.team(Set.of(1L)));
        MaterialRevision global = new MaterialRevision(11L, TENANT, 10L, 1, "全局合成素材", "合成摘要",
                "CASE", null, 20L, "310100", "[]", 99L, "合成变更", "APPROVED", null, null,
                null, 7L, Instant.parse("2026-10-27T08:00:00Z"), Instant.parse("2026-10-27T08:00:00Z"), 2L);
        when(repository.findScopedRevision(eq(TENANT), eq(10L), eq(11L), any())).thenReturn(Optional.of(global));

        assertThatThrownBy(() -> service.download(TENANT, READER, 10, 11))
                .isInstanceOf(BusinessException.class);

        verify(fileService, never()).download(anyLong(), any(), anyLong());
    }

    @Test
    void rejectedRevisionIsAlsoImmutable() {
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any()))
                .thenReturn(Optional.of(material("REJECTED", 11L)));
        when(repository.findScopedRevisionForUpdate(eq(TENANT), eq(10L), eq(11L), any()))
                .thenReturn(Optional.of(revision(11, 1, "REJECTED", 7)));

        assertThatThrownBy(() -> service.update(TENANT, WRITER, 10, updateRequest("REJECTED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("草稿");

        verify(repository, never()).updateDraft(any(), any());
    }

    @Test
    void rejectionRequiresReviewNote() {
        assertThatThrownBy(() -> service.review(TENANT, REVIEWER, 10, 11,
                new ReviewMaterialRevisionRequest(2L, "REJECTED", " ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("审核意见");

        verify(repository, never()).reviewRevision(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    void globalMaterialRequiresAllScope() {
        when(dataScopeService.scopeFor(WRITER, DataScopeService.RESOURCE_INSTITUTION)).thenReturn(DataScope.none());

        assertThatThrownBy(() -> service.create(TENANT, WRITER, createRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("全局素材");

        verify(fileService, never()).requireCleanUsable(anyLong(), any(), anyLong(), any(FileClassification.class), any());
        verify(repository, never()).insertMaterial(any());
    }

    @Test
    void institutionWriterCannotCreateForAnotherInstitution() {
        CurrentUser institutionWriter = user(7, "INSTITUTION", Set.of("material:read", "material:write"), Set.of(1L));
        when(dataScopeService.scopeFor(institutionWriter, DataScopeService.RESOURCE_INSTITUTION))
                .thenReturn(DataScope.institution(Set.of(1L)));

        assertThatThrownBy(() -> service.create(TENANT, institutionWriter, createRequest(2L)))
                .isInstanceOf(BusinessException.class);

        verify(repository, never()).insertMaterial(any());
    }

    @Test
    void searchNormalizesFiltersAndReturnsOnlyScopedVisibleRevision() {
        MaterialSearchCriteria request = new MaterialSearchCriteria(" 合成 ", "CASE", 1L, 20L, "310100",
                "APPROVED", false, 2, 10);
        MaterialSearchCriteria normalized = new MaterialSearchCriteria("合成", "CASE", 1L, 20L, "310100",
                "APPROVED", false, 2, 10);
        Instant now = Instant.parse("2026-10-27T08:00:00Z");
        MaterialListRow row = new MaterialListRow(10L, TENANT, "MT-SYNTHETIC", 11L, 7L, now, now, 3L,
                null, null, null, 11L, 1, "合成素材", "合成摘要", "CASE", 1L, 20L, "310100",
                "[\"合成标签\"]", 99L,
                "合成变更", "APPROVED", null, null, null, 7L, now, now, 2L);
        when(repository.search(eq(TENANT), any(), eq(normalized), eq(10), eq(10))).thenReturn(List.of(row));
        when(repository.count(eq(TENANT), any(), eq(normalized))).thenReturn(21L);

        var response = service.search(TENANT, READER, request);

        assertThat(response.total()).isEqualTo(21L);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().tags()).containsExactly("合成标签");
        assertThat(response.items().getFirst().revisions()).hasSize(1);
        ArgumentCaptor<MaterialScopeQuery> scopeCaptor = ArgumentCaptor.forClass(MaterialScopeQuery.class);
        verify(repository).search(eq(TENANT), scopeCaptor.capture(), eq(normalized), eq(10), eq(10));
        assertThat(scopeCaptor.getValue().canRead()).isTrue();
        assertThat(scopeCaptor.getValue().canWrite()).isFalse();
        assertThat(scopeCaptor.getValue().canReview()).isFalse();
    }

    @Test
    void offlineUsesOptimisticLockAndAuditWithoutRewritingApprovedRevision() {
        Material approved = material("APPROVED", 11L);
        MaterialRevision approvedRevision = revision(11, 1, "APPROVED", 7);
        Instant offlineAt = Instant.parse("2026-10-28T08:00:00Z");
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any())).thenReturn(Optional.of(approved));
        when(repository.offlineMaterial(eq(TENANT), eq(10L), eq(3L), eq(7L), eq("授权到期"), any(), any()))
                .thenReturn(1);
        stubDetail(material("APPROVED", 11L, offlineAt), List.of(approvedRevision));

        MaterialResponse response = service.offline(TENANT, WRITER, 10L,
                new OfflineMaterialRequest(3L, "授权到期"), "offline-key");

        assertThat(response.offlineAt()).isEqualTo(offlineAt);
        assertThat(response.reviewStatus()).isEqualTo("APPROVED");
        assertThat(response.currentRevisionId()).isEqualTo(11L);
        assertThat(response.revisions().getFirst().status()).isEqualTo("APPROVED");
        verify(repository).offlineMaterial(eq(TENANT), eq(10L), eq(3L), eq(7L), eq("授权到期"), any(), any());
        verify(repository, never()).reviewMaterial(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any(), any());
        verify(auditService).record(eq(TENANT), eq(7L), eq("MATERIAL_OFFLINE"), eq("MATERIAL"),
                eq("10"), any(), any(), eq("授权到期"));
        verify(idempotencyService).recordSuccess(eq(TENANT), eq("material.offline"), eq("offline-key"),
                eq("MATERIAL"), eq("10"), anyString());
    }

    @Test
    void offlineRejectsStaleMaterialVersion() {
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any()))
                .thenReturn(Optional.of(material("APPROVED", 11L)));

        assertThatThrownBy(() -> service.offline(TENANT, WRITER, 10L,
                new OfflineMaterialRequest(2L, "授权到期"), "offline-stale"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("版本不一致");

        verify(repository, never()).offlineMaterial(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any(), any());
        verify(auditService, never()).record(anyLong(), anyLong(), eq("MATERIAL_OFFLINE"), any(), any(), any(), any(), any());
    }

    @Test
    void offlineIdempotentReplayDoesNotRepeatWriteOrAudit() {
        Material offline = material("APPROVED", 11L, Instant.parse("2026-10-28T08:00:00Z"));
        IdempotencyRecord record = new IdempotencyRecord();
        record.setResourceType("MATERIAL");
        record.setResourceId("10");
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any())).thenReturn(Optional.of(offline));
        when(idempotencyService.lookup(eq(TENANT), eq("material.offline"), eq("offline-replay"), anyString()))
                .thenReturn(Optional.of(record));
        stubDetail(offline, List.of(revision(11, 1, "APPROVED", 7)));

        MaterialResponse response = service.offline(TENANT, WRITER, 10L,
                new OfflineMaterialRequest(3L, "授权到期"), "offline-replay");

        assertThat(response.offlineAt()).isNotNull();
        verify(repository, never()).offlineMaterial(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any(), any());
        verify(auditService, never()).record(anyLong(), anyLong(), eq("MATERIAL_OFFLINE"), any(), any(), any(), any(), any());
        verify(idempotencyService, never()).recordSuccess(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void offlineMaterialFreezesRevisionSubmissionAndReview() {
        Material offline = material("PENDING", 11L, Instant.parse("2026-10-28T08:00:00Z"));
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any())).thenReturn(Optional.of(offline));

        assertThatThrownBy(() -> service.submit(TENANT, WRITER, 10L, 12L,
                new SubmitMaterialRevisionRequest(2L)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已下架");
        assertThatThrownBy(() -> service.review(TENANT, REVIEWER, 10L, 12L,
                new ReviewMaterialRevisionRequest(2L, "APPROVED", "通过")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已下架");

        verify(repository, never()).submitRevision(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(repository, never()).reviewRevision(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    void materialWithoutApprovedRevisionCannotBeOfflined() {
        when(repository.findScopedMaterialForUpdate(eq(TENANT), eq(10L), any()))
                .thenReturn(Optional.of(material("DRAFT", null)));

        assertThatThrownBy(() -> service.offline(TENANT, WRITER, 10L,
                new OfflineMaterialRequest(3L, "草稿清理"), "offline-draft"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已审核版本");

        verify(repository, never()).offlineMaterial(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any(), any());
    }

    @Test
    void knowledgePublisherCanReadApprovedRevisionWithoutMaterialWritePermission() {
        CurrentUser publisher = user(1, "EMPLOYEE", Set.of("ai:knowledge:publish"), Set.of());
        when(repository.findScopedMaterial(eq(TENANT), eq(10L), any()))
                .thenReturn(Optional.of(material("APPROVED", 11L)));
        when(repository.findScopedRevision(eq(TENANT), eq(10L), eq(11L), any()))
                .thenReturn(Optional.of(revision(11, 1, "APPROVED", 7)));

        MaterialRevision result = service.getApprovedRevisionForKnowledge(TENANT, publisher, 10L, 11L);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.fileId()).isEqualTo(99L);
    }

    @Test
    void knowledgeSourceRequiresPublishPermissionAndApprovedRevision() {
        assertThatThrownBy(() -> service.getApprovedRevisionForKnowledge(TENANT, WRITER, 10L, 11L))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).findScopedRevision(anyLong(), anyLong(), anyLong(), any());

        CurrentUser publisher = user(1, "EMPLOYEE", Set.of("ai:knowledge:publish"), Set.of());
        when(repository.findScopedMaterial(eq(TENANT), eq(10L), any()))
                .thenReturn(Optional.of(material("DRAFT", null)));
        when(repository.findScopedRevision(eq(TENANT), eq(10L), eq(11L), any()))
                .thenReturn(Optional.of(revision(11, 1, "DRAFT", 7)));
        assertThatThrownBy(() -> service.getApprovedRevisionForKnowledge(TENANT, publisher, 10L, 11L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("APPROVED");
    }

    @Test
    void offlineMaterialCannotBePublishedToKnowledgeEvenByPrivilegedPublisher() {
        CurrentUser publisher = user(1, "EMPLOYEE",
                Set.of("ai:knowledge:publish", "material:write"), Set.of());
        when(repository.findScopedMaterial(eq(TENANT), eq(10L), any())).thenReturn(Optional.of(
                material("APPROVED", 11L, Instant.parse("2026-10-28T08:00:00Z"))));

        assertThatThrownBy(() -> service.getApprovedRevisionForKnowledge(TENANT, publisher, 10L, 11L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已下架");

        verify(repository, never()).findScopedRevision(anyLong(), anyLong(), anyLong(), any());
    }

    private void stubDetail(Material material, List<MaterialRevision> revisions) {
        when(repository.findScopedMaterial(eq(TENANT), eq(10L), any())).thenReturn(Optional.of(material));
        when(repository.findScopedRevisions(eq(TENANT), eq(10L), any())).thenReturn(revisions);
    }

    private static CurrentUser user(long id, String type, Set<String> permissions, Set<Long> institutionIds) {
        return new CurrentUser(id, TENANT, "synthetic-" + id, "合成用户", type, null,
                Set.of("SYNTHETIC"), permissions, institutionIds);
    }

    private static Material material(String status, Long currentRevisionId) {
        return material(status, currentRevisionId, null);
    }

    private static Material material(String status, Long currentRevisionId, Instant offlineAt) {
        return new Material(10L, TENANT, "MT-SYNTHETIC", "合成素材", "CASE", 1L, 20L, "310100",
                "合成摘要", "[\"合成标签\"]", currentRevisionId, status, null, offlineAt,
                offlineAt == null ? null : 8L, offlineAt == null ? null : "合成下架原因", 7L,
                Instant.parse("2026-10-27T08:00:00Z"), Instant.parse("2026-10-27T08:00:00Z"),
                offlineAt == null ? 3L : 4L);
    }

    private static MaterialRevision revision(long id, int revisionNo, String status, long createdBy) {
        return new MaterialRevision(id, TENANT, 10L, revisionNo, "合成素材", "合成摘要", "CASE", 1L, 20L,
                "310100", "[\"合成标签\"]", 99L, "合成变更", status, null, null, null, createdBy,
                Instant.parse("2026-10-27T08:00:00Z"), Instant.parse("2026-10-27T08:00:00Z"), 2L);
    }

    private static FileMetadata cleanFile(Long institutionId) {
        return new FileMetadata(99L, TENANT, "material/1/synthetic", "synthetic.pdf", "application/pdf",
                100L, "a".repeat(64), "MATERIAL", institutionId, "CLEAN", 7L, "etag", null, null,
                Instant.parse("2026-10-27T08:00:00Z"), Instant.parse("2026-10-27T08:00:00Z"), 2L);
    }

    private static CreateMaterialRequest createRequest(Long institutionId) {
        return new CreateMaterialRequest("合成素材", "CASE", institutionId, 20L, "310100", "合成摘要",
                List.of("合成标签"), 99L, "首个合成版本");
    }

    private static UpdateMaterialRequest updateRequest(String ignored) {
        return new UpdateMaterialRequest(11L, 2L, "合成素材修改", "CASE", 1L, 20L, "310100", "合成摘要",
                List.of("合成标签"), 99L, "合成修改");
    }

    private static CreateMaterialRevisionRequest revisionRequest() {
        return new CreateMaterialRevisionRequest(3L, "合成素材新修订", "CASE", 1L, 20L, "310100", "合成摘要",
                List.of("合成标签"), 99L, "重新送审");
    }
}
