package com.whale.crm.customer.batchtag.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.application.CustomerSegmentApplicationService;
import com.whale.crm.customer.batchtag.api.dto.BatchTagJobActionRequest;
import com.whale.crm.customer.batchtag.api.dto.BatchTagJobDetailResponse;
import com.whale.crm.customer.batchtag.api.dto.BatchTagPreviewResponse;
import com.whale.crm.customer.batchtag.api.dto.BatchTagSourceRequest;
import com.whale.crm.customer.batchtag.api.dto.PreviewBatchTagJobRequest;
import com.whale.crm.customer.batchtag.domain.BatchTagJob;
import com.whale.crm.customer.batchtag.domain.BatchTagJobItem;
import com.whale.crm.customer.batchtag.domain.BatchTagJobRepository;
import com.whale.crm.customer.batchtag.domain.BatchTagPreviewMember;
import com.whale.crm.customer.domain.CustomerListRow;
import com.whale.crm.customer.domain.CustomerSegmentBatch;
import com.whale.crm.customer.domain.CustomerSegmentRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchTagJobApplicationServiceTest {
    private final BatchTagJobRepository repository = mock(BatchTagJobRepository.class);
    private final CustomerSegmentApplicationService segmentService = mock(CustomerSegmentApplicationService.class);
    private final CustomerSegmentRepository segmentRepository = mock(CustomerSegmentRepository.class);
    private final CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
    private final DictionaryApplicationService dictionaryService = mock(DictionaryApplicationService.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BatchTagJobExecutionService executionService = mock(BatchTagJobExecutionService.class);
    private final CurrentUser user = new CurrentUser(1, 1, "admin", "合成管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("customer:read", "customer:write"), Set.of());
    private BatchTagJobApplicationService service;

    @BeforeEach
    void setUp() {
        service = new BatchTagJobApplicationService(repository, segmentService, segmentRepository, scopeSupport,
                dictionaryService, idempotencyService, auditService, executionService,
                new BatchTagJson(new ObjectMapper()));
        when(scopeSupport.scope(user)).thenReturn(DataScope.all());
        when(scopeSupport.queryOf(any(), eq(user))).thenReturn(
                new CustomerScopeSupport.ScopeQuery("ALL", List.of(), user.userId()));
        when(idempotencyService.lookup(anyLong(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void operationBatchPreviewCopiesVisibleFixedMembersIntoImmutablePreview() {
        CustomerSegmentBatch batch = new CustomerSegmentBatch(9L, 1L, 7L, "SG-V33", "FOLLOWUP",
                "{\"cityCodes\":[\"BJ\"]}", 1, 1L, null);
        when(segmentRepository.findBatchById(1, 9)).thenReturn(Optional.of(batch));
        when(repository.countOperationBatchMembers(eq(1L), eq(9L), eq("ALL"), any(), eq(1L))).thenReturn(1L);
        CustomerListRow row = new CustomerListRow();
        row.setId(11L);
        row.setTenantId(1L);
        row.setCustomerNo("C0000000011");
        row.setNickname("合成客户");
        row.setOwnerStaffId(2L);
        row.setTeamId(1L);
        when(repository.findOperationBatchMembers(eq(1L), eq(9L), eq("ALL"), any(), eq(1L), eq(0), eq(500)))
                .thenReturn(List.of(row));
        BatchTagPreviewMember stored = new BatchTagPreviewMember(1L, 1L, "BP", 11L, "C0000000011",
                "合成客户", 2L, 1L, null);
        when(repository.findPreviewMembers(eq(1L), anyString(), eq(0), eq(20))).thenReturn(List.of(stored));

        BatchTagPreviewResponse response = service.preview(1, user,
                new PreviewBatchTagJobRequest("REMOVE", List.of("LEGACY_UNKNOWN"),
                        new BatchTagSourceRequest("OPERATION_BATCH", 9L, null, null), 1, 20), "preview-key");

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.sourceType()).isEqualTo("OPERATION_BATCH");
        assertThat(response.sourceId()).isEqualTo(9L);
        assertThat(response.items().getFirst().customerId()).isEqualTo(11L);
        ArgumentCaptor<BatchTagPreviewMember> member = ArgumentCaptor.forClass(BatchTagPreviewMember.class);
        verify(repository).insertPreviewMember(member.capture());
        assertThat(member.getValue().customerId()).isEqualTo(11L);
        assertThat(member.getValue().customerNo()).isEqualTo("C0000000011");
        verify(idempotencyService).recordSuccess(eq(1L), eq("customer:batch_tag:preview"), eq("preview-key"),
                eq("BATCH_TAG_PREVIEW"), anyString(), anyString());
    }

    @Test
    void rollbackCreatesIndependentInverseJobFromActuallyChangedItems() {
        BatchTagJob original = job(1L, BatchTagJob.REMOVE, BatchTagJob.SUCCEEDED, null, 2L);
        BatchTagJob rollback = job(2L, BatchTagJob.ADD, BatchTagJob.PENDING, 1L, 0L);
        BatchTagJobItem changed = new BatchTagJobItem(10L, 1L, 1L, 11L, "C0000000011", "合成客户",
                2L, 1L, "[\"V34_REMOVE\"]", BatchTagJobItem.SUCCEEDED, null, null,
                "[\"LEGACY_UNKNOWN\",\"V34_REMOVE\"]", "[\"LEGACY_UNKNOWN\"]", "[\"V34_REMOVE\"]",
                1, null, 1L);
        when(repository.findJobById(1, 1)).thenReturn(Optional.of(original));
        when(repository.findJobById(1, 2)).thenReturn(Optional.of(rollback));
        when(repository.findJobByRollbackOf(1, 1)).thenReturn(Optional.empty());
        when(repository.findRollbackCandidates(1, 1)).thenReturn(List.of(changed));
        when(repository.findJobByNo(eq(1L), anyString())).thenReturn(Optional.of(rollback));
        when(dictionaryService.requireActiveOption(1L, "customerTags", "V34_REMOVE"))
                .thenReturn(new DictionaryOptionResponse("V34_REMOVE", "待移除", 1));

        BatchTagJobDetailResponse response = service.rollback(1, user, 1,
                new BatchTagJobActionRequest(2L), "rollback-key");

        assertThat(response.job().id()).isEqualTo(2L);
        assertThat(response.job().operation()).isEqualTo(BatchTagJob.ADD);
        assertThat(response.job().rollbackOfJobId()).isEqualTo(1L);
        assertThat(original.operation()).isEqualTo(BatchTagJob.REMOVE);
        ArgumentCaptor<BatchTagJob> inserted = ArgumentCaptor.forClass(BatchTagJob.class);
        verify(repository).insertJob(inserted.capture());
        assertThat(inserted.getValue().sourceType()).isEqualTo("ROLLBACK");
        assertThat(inserted.getValue().rollbackOfJobId()).isEqualTo(1L);
        assertThat(inserted.getValue().totalCount()).isEqualTo(1);
        ArgumentCaptor<BatchTagJobItem> item = ArgumentCaptor.forClass(BatchTagJobItem.class);
        verify(repository).insertItem(item.capture());
        assertThat(item.getValue().tagCodesJson()).isEqualTo("[\"V34_REMOVE\"]");
        verify(idempotencyService).recordSuccess(eq(1L), eq("customer:batch_tag:rollback"), eq("rollback-key"),
                eq("BATCH_TAG_JOB"), eq("2"), anyString());
    }

    private BatchTagJob job(Long id, String operation, String status, Long rollbackOf, long version) {
        return new BatchTagJob(id, 1L, "BT" + id, null, 1L, operation, "[\"V34_REMOVE\"]", "[]",
                rollbackOf == null ? "FILTER" : "ROLLBACK", rollbackOf, "{}", "{\"type\":\"ALL\"}",
                "key", rollbackOf, status, 1, 0, 0, 0, 0, 0, null, null, null,
                null, null, null, version);
    }
}
