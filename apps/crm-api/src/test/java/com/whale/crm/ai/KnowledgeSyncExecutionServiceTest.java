package com.whale.crm.ai;

import com.whale.crm.ai.application.AiWorkerException;
import com.whale.crm.ai.application.DifyKnowledgeConfigService;
import com.whale.crm.ai.application.KnowledgeSyncExecutionService;
import com.whale.crm.ai.application.KnowledgeWorkerClient;
import com.whale.crm.ai.domain.KnowledgeDocument;
import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.ai.domain.KnowledgeSyncJob;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.application.KnowledgeTextContent;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.material.application.MaterialApplicationService;
import com.whale.crm.material.domain.MaterialRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeSyncExecutionServiceTest {
    private static final String HASH = "a".repeat(64);
    private static final String OWNER = "worker-1";
    @Mock KnowledgeRepository repository;
    @Mock MaterialApplicationService materialService;
    @Mock FileApplicationService fileService;
    @Mock DifyKnowledgeConfigService configService;
    @Mock KnowledgeWorkerClient workerClient;
    @Mock PlatformTransactionManager transactionManager;

    private KnowledgeSyncExecutionService service;
    private KnowledgeWorkerClient.DatasetProviderConfig config;

    @BeforeEach
    void setUp() {
        service = new KnowledgeSyncExecutionService(repository, materialService, fileService, configService,
                workerClient, transactionManager);
        config = new KnowledgeWorkerClient.DatasetProviderConfig("mock", "", "", "mock-dataset", 30);
    }

    @Test
    void mockAvailableActivatesRevisionInOneTransaction() {
        KnowledgeSyncJob job = job("RUNNING", null, null);
        KnowledgeDocument document = document(null, 0L);
        stubSource(job, document, HASH);
        when(configService.datasetWorkerConfig(1L)).thenReturn(config);
        when(workerClient.syncDocument(eq(config), any(), anyString()))
                .thenReturn(new KnowledgeWorkerClient.DocumentSyncResult("req", "mock", "doc-1", "batch-1", "AVAILABLE", null));
        when(repository.markSucceeded(eq(1L), eq(501L), eq(3L), eq(OWNER), any(java.time.Instant.class))).thenReturn(1);
        when(repository.activateDocument(eq(1L), eq(101L), eq(0L), eq(20L), eq("mock-dataset"), eq("doc-1"), any())).thenReturn(1);

        service.execute(1L, 501L, OWNER);

        verify(repository).markSucceeded(eq(1L), eq(501L), eq(3L), eq(OWNER), any(Instant.class));
        verify(repository).activateDocument(eq(1L), eq(101L), eq(0L), eq(20L), eq("mock-dataset"), eq("doc-1"), any(Instant.class));
        verify(repository, never()).markRetry(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void disabledDocumentStopsBeforeReadingConfigOrCallingWorker() {
        KnowledgeSyncJob job = job("RUNNING", null, null);
        KnowledgeDocument document = new KnowledgeDocument(101L, 1L, 10L, null, "mock-dataset", null,
                "DISABLED", 1L, null, Instant.now(), Instant.now(), 1L);
        when(repository.findSyncJobById(1L, 501L)).thenReturn(Optional.of(job));
        when(repository.findDocumentById(1L, 101L)).thenReturn(Optional.of(document));

        service.execute(1L, 501L, OWNER);

        verify(configService, never()).datasetWorkerConfig(anyLong());
        verify(workerClient, never()).syncDocument(any(), any(), anyString());
        verify(repository, never()).markRetry(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
        verify(repository, never()).activateDocument(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void indexingKeepsPreviousActiveRevision() {
        KnowledgeSyncJob job = job("RUNNING", "doc-1", "batch-1");
        KnowledgeDocument document = document(10L, 4L);
        when(repository.findSyncJobById(1L, 501L)).thenReturn(Optional.of(job));
        when(repository.findDocumentById(1L, 101L)).thenReturn(Optional.of(document));
        when(configService.datasetWorkerConfig(1L)).thenReturn(config);
        when(workerClient.documentStatus(eq(config), eq("doc-1"), eq("batch-1"), anyString()))
                .thenReturn(new KnowledgeWorkerClient.DocumentSyncResult("req", "mock", "doc-1", "batch-1", "INDEXING", null));
        when(repository.markIndexing(eq(1L), eq(501L), eq(3L), eq(OWNER), eq("doc-1"), eq("batch-1"), any())).thenReturn(1);

        service.execute(1L, 501L, OWNER);

        verify(repository, never()).activateDocument(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
        verify(repository, never()).markDocumentSyncing(anyLong(), anyLong(), anyLong());
        verify(materialService, never()).getApprovedRevisionForKnowledge(anyLong(), anyLong(), anyLong());
    }

    @Test
    void indexingAvailableSwitchesToNewRevision() {
        KnowledgeSyncJob job = job("RUNNING", "doc-1", "batch-1");
        KnowledgeDocument document = document(10L, 4L);
        when(repository.findSyncJobById(1L, 501L)).thenReturn(Optional.of(job));
        when(repository.findDocumentById(1L, 101L)).thenReturn(Optional.of(document));
        when(configService.datasetWorkerConfig(1L)).thenReturn(config);
        when(workerClient.documentStatus(eq(config), eq("doc-1"), eq("batch-1"), anyString()))
                .thenReturn(new KnowledgeWorkerClient.DocumentSyncResult("req", "mock", "doc-1", "batch-1", "AVAILABLE", null));
        when(repository.markSucceeded(eq(1L), eq(501L), eq(3L), eq(OWNER), any())).thenReturn(1);
        when(repository.activateDocument(eq(1L), eq(101L), eq(4L), eq(20L), eq("mock-dataset"), eq("doc-1"), any())).thenReturn(1);

        service.execute(1L, 501L, OWNER);

        verify(repository).activateDocument(eq(1L), eq(101L), eq(4L), eq(20L), eq("mock-dataset"), eq("doc-1"), any());
    }

    @Test
    void transientWorkerFailureUsesBoundedRetry() {
        KnowledgeSyncJob job = job("RUNNING", null, null);
        KnowledgeDocument document = document(null, 0L);
        stubSource(job, document, HASH);
        when(configService.datasetWorkerConfig(1L)).thenReturn(config);
        when(workerClient.syncDocument(eq(config), any(), anyString())).thenThrow(new AiWorkerException("unavailable", false));
        when(repository.markRetry(eq(1L), eq(501L), eq(3L), eq(OWNER), eq("WORKER_UNAVAILABLE"), any())).thenReturn(1);
        when(repository.markDocumentSyncing(1L, 101L, 0L)).thenReturn(1);

        service.execute(1L, 501L, OWNER);

        verify(repository).markRetry(eq(1L), eq(501L), eq(3L), eq(OWNER), eq("WORKER_UNAVAILABLE"), any());
        verify(repository, never()).markFailed(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void sourceHashMismatchFailsWithoutCallingWorker() {
        KnowledgeSyncJob job = job("RUNNING", null, null);
        KnowledgeDocument document = document(null, 0L);
        stubSource(job, document, "b".repeat(64));
        when(configService.datasetWorkerConfig(1L)).thenReturn(config);
        when(repository.markFailed(eq(1L), eq(501L), eq(3L), eq(OWNER), eq("SOURCE_CHANGED"), any(java.time.Instant.class))).thenReturn(1);
        when(repository.markDocumentFailed(1L, 101L, 0L)).thenReturn(1);

        service.execute(1L, 501L, OWNER);

        verify(workerClient, never()).syncDocument(any(), any(), anyString());
        verify(repository).markFailed(eq(1L), eq(501L), eq(3L), eq(OWNER), eq("SOURCE_CHANGED"), any());
    }

    private void stubSource(KnowledgeSyncJob job, KnowledgeDocument document, String fileHash) {
        when(repository.findSyncJobById(1L, 501L)).thenReturn(Optional.of(job));
        when(repository.findDocumentById(1L, 101L)).thenReturn(Optional.of(document));
        when(materialService.getApprovedRevisionForKnowledge(1L, 10L, 20L)).thenReturn(revision());
        FileMetadata metadata = new FileMetadata(99L, 1L, "material/1/guide", "guide.md", "text/markdown",
                100L, fileHash, "MATERIAL", 1L, "CLEAN", 7L, "etag", null, null,
                Instant.now(), Instant.now(), 2L);
        when(fileService.readKnowledgeText(1L, 99L, 1L, 1_048_576L))
                .thenReturn(new KnowledgeTextContent(metadata, "# 合成流程"));
    }

    private MaterialRevision revision() {
        return new MaterialRevision(20L, 1L, 10L, 2, "合成流程", "摘要", "GUIDE", 1L, 30L,
                "310100", "[]", 99L, null, "APPROVED", 8L, null, Instant.now(), 7L,
                Instant.now(), Instant.now(), 2L);
    }

    private KnowledgeDocument document(Long activeRevisionId, long version) {
        return new KnowledgeDocument(101L, 1L, 10L, activeRevisionId, "mock-dataset", null,
                activeRevisionId == null ? "WAITING" : "ACTIVE", 1L, null, Instant.now(), Instant.now(), version);
    }

    private KnowledgeSyncJob job(String status, String documentId, String batch) {
        return new KnowledgeSyncJob(501L, 1L, 101L, 20L, HASH, status, 1, 5, null, OWNER,
                Instant.now().plusSeconds(30), documentId, batch, null, 1L, "publish-1", "request-hash",
                Instant.now(), null, Instant.now(), Instant.now(), 3L);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
