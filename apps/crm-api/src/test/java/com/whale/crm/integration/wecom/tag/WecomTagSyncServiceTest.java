package com.whale.crm.integration.wecom.tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerApplicationService;
import com.whale.crm.customer.application.CustomerProjectSupport;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.application.MobileSupport;
import com.whale.crm.customer.application.StewardDirectory;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerAttentionProjectRepository;
import com.whale.crm.customer.domain.CustomerIdentityRepository;
import com.whale.crm.customer.domain.CustomerOwnerHistoryRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.customer.domain.PointsLedgerRepository;
import com.whale.crm.customer.tier.application.CustomerTierReviewService;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.tag.adapter.WecomCorpTag;
import com.whale.crm.integration.wecom.tag.adapter.WecomTagClient;
import com.whale.crm.integration.wecom.tag.adapter.WecomTagClientRouter;
import com.whale.crm.integration.wecom.tag.api.dto.WecomTagPushTaskResponse;
import com.whale.crm.integration.wecom.tag.api.dto.WecomTagSyncRunResponse;
import com.whale.crm.integration.wecom.tag.application.WecomTagSyncService;
import com.whale.crm.integration.wecom.tag.domain.WecomCustomerTagPushTask;
import com.whale.crm.integration.wecom.tag.domain.WecomLinkedContactForTagSync;
import com.whale.crm.integration.wecom.tag.domain.WecomTagMapping;
import com.whale.crm.integration.wecom.tag.domain.WecomTagSyncRepository;
import com.whale.crm.integration.wecom.tag.domain.WecomTagSyncRun;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.api.dto.DictionaryItemResponse;
import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WecomTagSyncServiceTest {

    @Mock
    private WecomTagSyncRepository repository;
    @Mock
    private WecomTagClientRouter clientRouter;
    @Mock
    private WecomTagClient tagClient;
    @Mock
    private DictionaryApplicationService dictionaryService;
    @Mock
    private CustomerApplicationService customerService;
    @Mock
    private CustomerScopeSupport scopeSupport;
    @Mock
    private AuditService auditService;
    @Mock
    private IdempotencyService idempotencyService;

    private WecomTagSyncService service;

    @BeforeEach
    void setUp() {
        WecomOfficialProperties properties = new WecomOfficialProperties(1L, false, "ww-unit", "sec",
                "https://qyapi.weixin.qq.com", "tok", "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
                1000, 1000, 100, 10);
        service = new WecomTagSyncService(repository, clientRouter, properties, dictionaryService,
                customerService, scopeSupport, auditService, idempotencyService, new ObjectMapper());
    }

    @Test
    void parseWecomTagIds_supportsBothObjectArrayAndStringArrayAndMalformedJson() {
        assertThat(service.parseWecomTagIds("[{\"group_name\":\"组1\",\"tag_id\":\"et-1\"},{\"id\":\"et-2\"}]"))
                .containsExactly("et-1", "et-2");
        assertThat(service.parseWecomTagIds("[\"et-a\", \"et-b\", \"et-a\"]"))
                .containsExactly("et-a", "et-b");
        assertThat(service.parseWecomTagIds(null)).isEmpty();
        assertThat(service.parseWecomTagIds("not-json")).isEmpty();
    }

    @Test
    void syncCatalogPull_createsNewCrmTagAndMarksDeletedRemoteTagUnlinkedWithoutDeletingCrmTag() {
        when(clientRouter.client(1L)).thenReturn(tagClient);
        when(tagClient.corpId()).thenReturn("ww-unit");
        when(tagClient.name()).thenReturn("MOCK_WECOM_TAG_DEV_ONLY");
        when(repository.startRun(eq(1L), eq("ww-unit"), eq("CATALOG"), eq("PULL"), eq("MANUAL"),
                eq("MOCK_WECOM_TAG_DEV_ONLY"), eq(4L), any(), any())).thenReturn(11L);
        when(tagClient.listCorpTags()).thenReturn(List.of(
                new WecomCorpTag("grp-1", "默认组", "et-new", "企微新标签", 5)));
        DictionaryItemResponse existingLegacy = new DictionaryItemResponse(
                10L, "LEGACY_TAG", "历史标签", 1, "ACTIVE", 0L, Instant.now(), Instant.now());
        DictionaryItemResponse createdItem = new DictionaryItemResponse(
                11L, "TAG_NEW001", "企微新标签", 5, "ACTIVE", 0L, Instant.now(), Instant.now());
        when(dictionaryService.listItemsByDictCode(1L, "customerTags")).thenReturn(List.of(existingLegacy));
        when(repository.findMappings(1L, "ww-unit")).thenReturn(List.of(
                new WecomTagMapping(1L, 1L, "ww-unit", "LEGACY_TAG", "grp-1", "默认组", "et-gone",
                        "历史标签", "ACTIVE", Instant.now(), 4L, Instant.now(), Instant.now(), 0L)));
        when(dictionaryService.createItemForType(eq(1L), eq(4L), eq("customerTags"), anyString(),
                eq("企微新标签"), eq(5), eq(true))).thenReturn(createdItem);
        when(repository.findRunById(1L, 11L)).thenReturn(Optional.of(new WecomTagSyncRun(
                11L, 1L, "ww-unit", "CATALOG", "PULL", "MANUAL", "MOCK_WECOM_TAG_DEV_ONLY",
                "SUCCESS", 2, 0, 0, 0, null, null, Instant.now(), Instant.now(), 4L)));

        WecomTagSyncRunResponse response = service.syncCatalog(1L, 4L, "PULL", "MANUAL", null);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.pulledCount()).isEqualTo(2);
        verify(dictionaryService, never()).disableItemForType(anyLong(), anyLong(), anyString(), anyLong());
        verify(repository).upsertMapping(eq(1L), eq("ww-unit"), eq("LEGACY_TAG"), eq("grp-1"), eq("默认组"),
                isNull(), eq("历史标签"), eq("UNLINKED"), any(), eq(4L));
        verify(repository).upsertMapping(eq(1L), eq("ww-unit"), eq("TAG_NEW001"), eq("grp-1"), eq("默认组"),
                eq("et-new"), eq("企微新标签"), eq("ACTIVE"), any(), eq(4L));
    }

    @Test
    void syncCatalog_returns409WhenConcurrentRunAlreadyRunning() {
        when(clientRouter.client(1L)).thenReturn(tagClient);
        when(tagClient.corpId()).thenReturn("ww-unit");
        when(tagClient.name()).thenReturn("MOCK_WECOM_TAG_DEV_ONLY");
        when(repository.startRun(anyLong(), anyString(), eq("CATALOG"), eq("BIDIRECTIONAL"),
                anyString(), anyString(), any(), any(), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.syncCatalog(1L, 1L, "BIDIRECTIONAL", "MANUAL", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void onCustomerTagsChanged_enqueuesOnlyMappedDiffsAndNeverThrowsWhenExternalUnavailable() {
        when(clientRouter.client(1L)).thenReturn(tagClient);
        when(tagClient.corpId()).thenReturn("ww-unit");
        when(repository.findMappings(1L, "ww-unit")).thenReturn(List.of(
                new WecomTagMapping(1L, 1L, "ww-unit", "VIP", "g1", "组", "et-vip", "贵宾", "ACTIVE",
                        Instant.now(), 1L, Instant.now(), Instant.now(), 0L),
                new WecomTagMapping(2L, 1L, "ww-unit", "HIGH_INTENT", "g1", "组", "et-high", "高意向", "ACTIVE",
                        Instant.now(), 1L, Instant.now(), Instant.now(), 0L)));
        when(repository.findLinkedContactsByCustomerId(1L, "ww-unit", 1010L)).thenReturn(List.of(
                new WecomLinkedContactForTagSync(50L, "ww-unit", 1010L, 2L, "staff-2", "wm-ext-1010", "[]")));

        service.onCustomerTagsChanged(1L, 1010L, List.of("VIP", "LOCAL_ONLY"), List.of("HIGH_INTENT"), 2L, "CRM_WRITE");

        verify(repository).insertPushTask(eq(1L), eq("ww-unit"), eq(1010L), eq(50L), eq("staff-2"),
                eq("wm-ext-1010"), eq("[\"et-high\"]"), eq("[\"et-vip\"]"), eq(5), isNull(), eq("CRM_WRITE"), eq(2L));

        // 即使底层抛异常也不外抛，确保本地 CRM 客户标签写入事务不回滚
        when(clientRouter.client(1L)).thenThrow(new RuntimeException("redis down"));
        assertThatCode(() -> service.onCustomerTagsChanged(1L, 1010L, List.of(), List.of("VIP"), 2L, "CRM_WRITE"))
                .doesNotThrowAnyException();
    }

    @Test
    void pullCustomerTagsForExternalUser_updatesOnlyActiveMappedSubsetAndDoesNotEnqueuePushTask() {
        when(clientRouter.client(1L)).thenReturn(tagClient);
        when(tagClient.corpId()).thenReturn("ww-unit");
        when(dictionaryService.listActiveOptions(1L, "customerTags")).thenReturn(List.of(
                new DictionaryOptionResponse("VIP", "贵宾", 1),
                new DictionaryOptionResponse("RISK", "风险", 2)));
        when(repository.findMappings(1L, "ww-unit")).thenReturn(List.of(
                new WecomTagMapping(1L, 1L, "ww-unit", "VIP", "g1", "组", "et-vip", "贵宾", "ACTIVE",
                        Instant.now(), 1L, Instant.now(), Instant.now(), 0L),
                new WecomTagMapping(2L, 1L, "ww-unit", "RISK", "g1", "组", "et-risk", "风险", "ACTIVE",
                        Instant.now(), 1L, Instant.now(), Instant.now(), 0L),
                new WecomTagMapping(3L, 1L, "ww-unit", "DISABLED_TAG", "g1", "组", "et-disabled", "已停用", "ACTIVE",
                        Instant.now(), 1L, Instant.now(), Instant.now(), 0L)));
        when(repository.findLinkedContactsByExternalUserid(1L, "ww-unit", "wm-ext-1010")).thenReturn(List.of(
                new WecomLinkedContactForTagSync(50L, "ww-unit", 1010L, 2L, "staff-2", "wm-ext-1010",
                        "[{\"tag_id\":\"et-vip\"},{\"tag_id\":\"et-disabled\"}]")));
        when(customerService.syncTagsFromWecom(eq(1L), eq(1010L), eq(Set.of("VIP", "RISK")),
                eq(Set.of("VIP")), isNull(), anyString()))
                .thenReturn(new CustomerApplicationService.WecomTagPullOutcome(true, List.of("LOCAL"),
                        List.of("LOCAL", "VIP")));

        service.pullCustomerTagsForExternalUser(1L, "wm-ext-1010", "CALLBACK");

        verify(customerService).syncTagsFromWecom(eq(1L), eq(1010L), eq(Set.of("VIP", "RISK")),
                eq(Set.of("VIP")), isNull(), anyString());
        verify(repository, never()).insertPushTask(anyLong(), anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), any(), anyString(), any());
    }

    @Test
    void processPendingPushTasks_transitionsToRetryableThenFailedWithMaskedError() {
        WecomCustomerTagPushTask task = new WecomCustomerTagPushTask(
                88L, 1L, "ww-unit", 1010L, 50L, "staff-2", "wm-ext-1010",
                "[\"et-vip\"]", "[]", "PENDING", 0, 2, Instant.now(), null, null,
                "CRM_WRITE", 2L, Instant.now(), Instant.now(), 0L);
        when(repository.findPendingPushTasks(any(), eq(50))).thenReturn(List.of(task));
        when(clientRouter.client(1L)).thenReturn(tagClient);
        when(repository.claimPushTask(eq(1L), eq(88L), eq(0), any(), any())).thenReturn(1);
        doThrow(new BusinessException(ErrorCode.CONFLICT, "企微标签接口调用失败（错误码：45009）"))
                .when(tagClient).markCustomerTags("staff-2", "wm-ext-1010", List.of("et-vip"), List.of());

        int count = service.processPendingPushTasks();

        assertThat(count).isEqualTo(1);
        verify(repository).finishPushTask(eq(1L), eq(88L), eq(1), eq("RETRYABLE"), any(),
                eq("企微标签接口调用失败（错误码：45009）"));
    }

    @Test
    void listTasks_filtersOutCrossTeamTasksForTeamScope() {
        CurrentUser lead = new CurrentUser(4L, 1L, "lead1", "主管", "INTERNAL", 1L,
                Set.of("MANAGER_LEAD"), Set.of("wecom:monitor"), Set.of());
        when(scopeSupport.scope(lead)).thenReturn(DataScope.team(Set.of(1L)));
        when(clientRouter.client(1L)).thenReturn(tagClient);
        when(tagClient.corpId()).thenReturn("ww-unit");
        WecomCustomerTagPushTask team1Task = new WecomCustomerTagPushTask(
                101L, 1L, "ww-unit", 1010L, 50L, "staff-2", "wm-ext-1010",
                "[\"et-vip\"]", "[]", "RETRYABLE", 1, 5, Instant.now(), null, "err",
                "CRM_WRITE", 2L, Instant.now(), Instant.now(), 0L);
        WecomCustomerTagPushTask team2Task = new WecomCustomerTagPushTask(
                102L, 1L, "ww-unit", 1003L, 51L, "staff-3", "wm-ext-1003",
                "[\"et-vip\"]", "[]", "RETRYABLE", 1, 5, Instant.now(), null, "err",
                "CRM_WRITE", 3L, Instant.now(), Instant.now(), 0L);
        when(repository.findPushTasks(1L, "ww-unit", "RETRYABLE", 500))
                .thenReturn(List.of(team1Task, team2Task));
        when(customerService.filterVisibleCustomerIds(1L, lead, Set.of(1010L, 1003L)))
                .thenReturn(Set.of(1010L));

        List<WecomTagPushTaskResponse> result = service.listTasks(1L, lead, "RETRYABLE");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(101L);
        assertThat(result.get(0).customerId()).isEqualTo(1010L);
    }

    @Test
    void retryTask_rejectsCrossTeamTaskWithNotFoundBeforeResetting() {
        CurrentUser lead = new CurrentUser(4L, 1L, "lead1", "主管", "INTERNAL", 1L,
                Set.of("MANAGER_LEAD"), Set.of("wecom:monitor"), Set.of());
        WecomCustomerTagPushTask crossTeamTask = new WecomCustomerTagPushTask(
                102L, 1L, "ww-unit", 1003L, 51L, "staff-3", "wm-ext-1003",
                "[\"et-vip\"]", "[]", "RETRYABLE", 1, 5, Instant.now(), null, "err",
                "CRM_WRITE", 3L, Instant.now(), Instant.now(), 0L);
        when(repository.findPushTaskById(1L, 102L)).thenReturn(Optional.of(crossTeamTask));
        when(scopeSupport.requireVisible(1L, 1003L, lead))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));

        assertThatThrownBy(() -> service.retryTask(1L, lead, 102L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(repository, never()).resetPushTaskForRetry(anyLong(), anyLong(), any());
    }

    @Test
    void syncCustomers_restrictsLinkedContactsToVisibleDataScope() {
        CurrentUser lead = new CurrentUser(4L, 1L, "lead1", "主管", "INTERNAL", 1L,
                Set.of("MANAGER_LEAD"), Set.of("wecom:monitor"), Set.of());
        when(clientRouter.client(1L)).thenReturn(tagClient);
        when(tagClient.corpId()).thenReturn("ww-unit");
        when(tagClient.name()).thenReturn("MOCK_WECOM_TAG_DEV_ONLY");
        when(repository.startRun(eq(1L), eq("ww-unit"), eq("CUSTOMER"), eq("PULL"), eq("MANUAL"),
                eq("MOCK_WECOM_TAG_DEV_ONLY"), eq(4L), any(), any())).thenReturn(21L);
        when(repository.findMappings(1L, "ww-unit")).thenReturn(List.of(
                new WecomTagMapping(1L, 1L, "ww-unit", "VIP", "g1", "组", "et-vip", "贵宾", "ACTIVE",
                        Instant.now(), 1L, Instant.now(), Instant.now(), 0L)));
        when(dictionaryService.listActiveOptions(1L, "customerTags")).thenReturn(List.of(
                new DictionaryOptionResponse("VIP", "贵宾", 1)));
        when(repository.findAllLinkedContacts(1L, "ww-unit")).thenReturn(List.of(
                new WecomLinkedContactForTagSync(50L, "ww-unit", 1010L, 2L, "staff-2", "wm-ext-1010", "[\"et-vip\"]"),
                new WecomLinkedContactForTagSync(51L, "ww-unit", 1003L, 3L, "staff-3", "wm-ext-1003", "[\"et-vip\"]")));
        when(scopeSupport.scope(lead)).thenReturn(DataScope.team(Set.of(1L)));
        when(customerService.filterVisibleCustomerIds(1L, lead, Set.of(1010L, 1003L)))
                .thenReturn(Set.of(1010L));
        when(customerService.syncTagsFromWecom(eq(1L), eq(1010L), eq(Set.of("VIP")), eq(Set.of("VIP")),
                eq(4L), anyString()))
                .thenReturn(new CustomerApplicationService.WecomTagPullOutcome(true, List.of(), List.of("VIP")));
        when(repository.findRunById(1L, 21L)).thenReturn(Optional.of(new WecomTagSyncRun(
                21L, 1L, "ww-unit", "CUSTOMER", "PULL", "MANUAL", "MOCK_WECOM_TAG_DEV_ONLY",
                "SUCCESS", 1, 0, 0, 0, null, null, Instant.now(), Instant.now(), 4L)));

        WecomTagSyncRunResponse response = service.syncCustomers(1L, lead, "PULL", "MANUAL", null);

        assertThat(response.pulledCount()).isEqualTo(1);
        verify(customerService).syncTagsFromWecom(eq(1L), eq(1010L), eq(Set.of("VIP")), eq(Set.of("VIP")),
                eq(4L), anyString());
        verify(customerService, never()).syncTagsFromWecom(eq(1L), eq(1003L), any(), any(), any(), anyString());
    }

    @Test
    void customerSyncTagsFromWecom_neverInsertsDisabledTagAndPreservesHistoricalDisabledTag() {
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        CustomerTagRepository tagRepo = mock(CustomerTagRepository.class);
        CustomerApplicationService realCustomerService = new CustomerApplicationService(
                customerRepo, mock(CustomerIdentityRepository.class), tagRepo,
                mock(CustomerOwnerHistoryRepository.class), mock(PointsLedgerRepository.class),
                mock(UserRepository.class), scopeSupport, mock(MobileSupport.class), auditService,
                dictionaryService, idempotencyService, new ObjectMapper(), mock(StewardDirectory.class),
                mock(CustomerTierReviewService.class), mock(CustomerAttentionProjectRepository.class),
                mock(CustomerProjectSupport.class));

        Customer c1 = new Customer();
        c1.setId(1010L);
        c1.setDeleted(false);
        Customer c2 = new Customer();
        c2.setId(1003L);
        c2.setDeleted(false);
        when(customerRepo.findById(1L, 1010L)).thenReturn(Optional.of(c1));
        when(customerRepo.findById(1L, 1003L)).thenReturn(Optional.of(c2));
        when(dictionaryService.listActiveOptions(1L, "customerTags")).thenReturn(List.of(
                new DictionaryOptionResponse("VIP", "贵宾", 1)));

        // 客户 1010 未持有 DISABLED_TAG：即便调用方传入 DISABLED_TAG，也不得新写入
        when(tagRepo.findTagCodes(1L, 1010L)).thenReturn(List.of(), List.of("VIP"));
        realCustomerService.syncTagsFromWecom(1L, 1010L, Set.of("VIP", "DISABLED_TAG"),
                Set.of("VIP", "DISABLED_TAG"), 1L, "test");
        verify(tagRepo).insertIfAbsent(1L, 1010L, "VIP", 1L);
        verify(tagRepo, never()).insertIfAbsent(eq(1L), eq(1010L), eq("DISABLED_TAG"), any());

        // 客户 1003 历史上已持有 DISABLED_TAG：即便目标集不包含它，也不得被删除（D01 非破坏原则）
        when(tagRepo.findTagCodes(1L, 1003L))
                .thenReturn(List.of("DISABLED_TAG", "LOCAL_ONLY"), List.of("DISABLED_TAG", "LOCAL_ONLY", "VIP"));
        realCustomerService.syncTagsFromWecom(1L, 1003L, Set.of("VIP", "DISABLED_TAG"),
                Set.of("VIP"), 1L, "test");
        verify(tagRepo).insertIfAbsent(1L, 1003L, "VIP", 1L);
        verify(tagRepo, never()).delete(1L, 1003L, "DISABLED_TAG");
        verify(tagRepo, never()).delete(1L, 1003L, "LOCAL_ONLY");
    }
}
