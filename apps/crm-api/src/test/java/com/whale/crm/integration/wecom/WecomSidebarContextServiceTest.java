package com.whale.crm.integration.wecom;

import com.whale.crm.customer.api.dto.CustomerDetailResponse;
import com.whale.crm.customer.application.CustomerApplicationService;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.integration.wecom.domain.WecomRoom;
import com.whale.crm.integration.wecom.domain.WecomRoomParticipant;
import com.whale.crm.integration.wecom.domain.WecomRoomRepository;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarGroupContextResponse;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarLinkContactRequest;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarLinkContactResponse;
import com.whale.crm.integration.wecom.sidebar.application.WecomSidebarContextService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.application.EntitlementApplicationService;
import com.whale.crm.trading.application.OrderApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomSidebarContextServiceTest {
    private WecomRepository wecomRepository;
    private WecomRoomRepository roomRepository;
    private CustomerApplicationService customerService;
    private CustomerScopeSupport scopeSupport;
    private AuditService auditService;
    private WecomSidebarContextService service;

    private final CurrentUser steward = new CurrentUser(2L, 1L, "manager1", "管家一", "EMPLOYEE", 1L,
            Set.of("MANAGER"), Set.of("wecom:sidebar:view", "customer:read", "customer:write"), Set.of());
    private final CurrentUser admin = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("wecom:sidebar:view", "customer:read", "customer:write"), Set.of());
    private final CurrentUser tenant2User = new CurrentUser(20L, 2L, "t2", "租户二", "EMPLOYEE", 2L,
            Set.of("MANAGER"), Set.of("wecom:sidebar:view", "customer:write"), Set.of());

    @BeforeEach
    void setUp() {
        wecomRepository = mock(WecomRepository.class);
        roomRepository = mock(WecomRoomRepository.class);
        customerService = mock(CustomerApplicationService.class);
        scopeSupport = mock(CustomerScopeSupport.class);
        auditService = mock(AuditService.class);
        WecomOfficialProperties props = new WecomOfficialProperties(1L, true, "ww-corp", "secret",
                "https://qyapi.weixin.qq.com", "", "", 3000, 3000, 100, 10);
        service = new WecomSidebarContextService(wecomRepository, roomRepository, props, customerService,
                scopeSupport, mock(OrderApplicationService.class), mock(EntitlementApplicationService.class),
                auditService);

        when(scopeSupport.scope(admin)).thenReturn(DataScope.all());
        when(scopeSupport.queryOf(DataScope.all(), admin))
                .thenReturn(new CustomerScopeSupport.ScopeQuery("ALL", List.of(), 1L));
        when(scopeSupport.scope(steward)).thenReturn(DataScope.self());
        when(scopeSupport.queryOf(DataScope.self(), steward))
                .thenReturn(new CustomerScopeSupport.ScopeQuery("SELF", List.of(), 2L));
    }

    @Test
    void groupContextHandlesWaitingAndUnregisteredRooms() {
        WecomSidebarGroupContextResponse waiting = service.groupContext(steward, "   ");
        assertThat(waiting.contextStatus()).isEqualTo("WAITING_CONTEXT");
        assertThat(waiting.room()).isNull();
        assertThat(waiting.customers()).isEmpty();

        when(roomRepository.findRoom(1L, "ww-corp", "wr-unknown")).thenReturn(Optional.empty());
        WecomSidebarGroupContextResponse unregistered = service.groupContext(steward, "wr-unknown");
        assertThat(unregistered.contextStatus()).isEqualTo("ROOM_UNREGISTERED");
        assertThat(unregistered.chatId()).isEqualTo("wr-unknown");
        assertThat(unregistered.room()).isNull();
        assertThat(unregistered.customers()).isEmpty();
    }

    @Test
    void groupContextFiltersLinkedCustomersByDataScopeAndRejectsUnauthorizedRoom() {
        WecomRoom room = new WecomRoom(10L, 1L, "ww-corp", "wr-vip-1", "VIP 美学群", "ACTIVE",
                "evidence", Instant.now(), 1L, Instant.now(), Instant.now(), 1L);
        when(roomRepository.findRoom(1L, "ww-corp", "wr-vip-1")).thenReturn(Optional.of(room));
        when(roomRepository.findParticipants(1L, "ww-corp", "wr-vip-1")).thenReturn(List.of(
                new WecomRoomParticipant(1L, "ww-corp", "wr-vip-1", "staff-2", "STAFF", 2L, null, "ACTIVE", Instant.now(), null, 0L),
                new WecomRoomParticipant(2L, "ww-corp", "wr-vip-1", "wo-visible", "EXTERNAL", null, "wo-visible", "ACTIVE", Instant.now(), null, 0L),
                new WecomRoomParticipant(3L, "ww-corp", "wr-vip-1", "wo-hidden", "EXTERNAL", null, "wo-hidden", "ACTIVE", Instant.now(), null, 0L),
                new WecomRoomParticipant(4L, "ww-corp", "wr-vip-1", "wo-removed", "EXTERNAL", null, "wo-removed", "REMOVED", Instant.now(), Instant.now(), 0L)
        ));
        when(wecomRepository.staffVisible(1L, 2L, "SELF", List.of(), 2L)).thenReturn(true);
        when(wecomRepository.findContactsByExternalUserid(1L, "ww-corp", "wo-visible"))
                .thenReturn(List.of(contact(100L, "wo-visible", 2L, 1010L, "ACTIVE")));
        when(wecomRepository.findContactsByExternalUserid(1L, "ww-corp", "wo-hidden"))
                .thenReturn(List.of(contact(101L, "wo-hidden", 3L, 2020L, "ACTIVE")));
        when(customerService.detail(1L, steward, 1010L)).thenReturn(customerDetail(1010L, "C1010", "林女士", "小林", "GOLD", "管家一", "138****8000"));
        when(customerService.detail(1L, steward, 2020L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "不可见"));

        WecomSidebarGroupContextResponse result = service.groupContext(steward, "wr-vip-1");

        assertThat(result.contextStatus()).isEqualTo("READY");
        assertThat(result.room().roomId()).isEqualTo("wr-vip-1");
        assertThat(result.room().displayName()).isEqualTo("VIP 美学群");
        assertThat(result.room().staffCount()).isEqualTo(1);
        assertThat(result.room().externalCount()).isEqualTo(2);
        assertThat(result.room().linkedCustomerCount()).isEqualTo(1);
        assertThat(result.customers()).hasSize(1);
        var c = result.customers().getFirst();
        assertThat(c.customerId()).isEqualTo(1010L);
        assertThat(c.customerNo()).isEqualTo("C1010");
        assertThat(c.displayName()).isEqualTo("林女士");
        assertThat(c.nickname()).isEqualTo("小林");
        assertThat(c.externalUserid()).isEqualTo("wo-visible");
        assertThat(c.tierCode()).isEqualTo("GOLD");
        assertThat(c.ownerName()).isEqualTo("管家一");
        assertThat(c.mobileMasked()).isEqualTo("138****8000");

        // Another team steward with no visible staff and no visible customer -> 404 NOT_FOUND
        CurrentUser otherSteward = new CurrentUser(9L, 1L, "manager2", "管家二", "EMPLOYEE", 2L,
                Set.of("MANAGER"), Set.of("wecom:sidebar:view"), Set.of());
        when(scopeSupport.scope(otherSteward)).thenReturn(DataScope.self());
        when(scopeSupport.queryOf(DataScope.self(), otherSteward))
                .thenReturn(new CustomerScopeSupport.ScopeQuery("SELF", List.of(), 9L));
        when(wecomRepository.staffVisible(1L, 2L, "SELF", List.of(), 9L)).thenReturn(false);
        when(customerService.detail(eq(1L), eq(otherSteward), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "不可见"));

        assertThatThrownBy(() -> service.groupContext(otherSteward, "wr-vip-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void groupContextRejectsDisallowedTenantAndEmptyScope() {
        assertThatThrownBy(() -> service.groupContext(tenant2User, "wr-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.PENDING_BUSINESS_RULE);

        CurrentUser inst = new CurrentUser(8L, 1L, "inst1", "机构员", "INSTITUTION", null,
                Set.of("INSTITUTION"), Set.of("wecom:sidebar:view"), Set.of(1L));
        when(scopeSupport.scope(inst)).thenReturn(DataScope.institution(Set.of(1L)));
        when(scopeSupport.isEmptyScope(DataScope.institution(Set.of(1L)))).thenReturn(true);
        assertThatThrownBy(() -> service.groupContext(inst, "wr-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void linkContactLinksExistingUnlinkedContactAndAudits() {
        WecomContact unlinked = contact(55L, "wo-unlinked", 2L, null, "ACTIVE");
        WecomContact linked = new WecomContact(55L, "ww-corp", "wo-unlinked", 2L,
                null, null, null, null, null, null, null, null, null, null, null, "ACTIVE",
                1010L, "PENDING", null, "MANUAL", "侧边栏核对绑定", 2L, Instant.now(), 1L);
        when(wecomRepository.findContactsByExternalUserid(1L, "ww-corp", "wo-unlinked")).thenReturn(List.of(unlinked));
        when(wecomRepository.findContactForUpdate(1L, "ww-corp", "wo-unlinked", 2L)).thenReturn(Optional.of(unlinked));
        when(wecomRepository.linkContact(1L, 55L, 0L, 1010L, "侧边栏核对绑定", 2L)).thenReturn(1);
        when(wecomRepository.findContactById(1L, 55L)).thenReturn(Optional.of(linked));

        WecomSidebarLinkContactResponse resp = service.linkContact(steward,
                new WecomSidebarLinkContactRequest("wo-unlinked", 1010L, "侧边栏核对绑定"));

        assertThat(resp.contactId()).isEqualTo(55L);
        assertThat(resp.customerId()).isEqualTo(1010L);
        assertThat(resp.alreadyLinked()).isFalse();
        assertThat(resp.linkType()).isEqualTo("MANUAL");
        verify(scopeSupport).requireVisible(1L, 1010L, steward);
        verify(wecomRepository).linkMessages(1L, "ww-corp", "wo-unlinked", 2L, 1010L);
        verify(auditService).record(eq(1L), eq(2L), eq("WECOM_SIDEBAR_CONTACT_LINK"),
                eq("WECOM_CONTACT"), eq("55"), any(), any(), eq("侧边栏核对绑定"));
    }

    @Test
    void linkContactCreatesNewPendingConsentContactWhenNotSyncedYet() {
        when(wecomRepository.findContactsByExternalUserid(1L, "ww-corp", "wo-brand-new")).thenReturn(List.of());
        when(wecomRepository.findContactForUpdate(1L, "ww-corp", "wo-brand-new", 2L)).thenReturn(Optional.empty());
        WecomContact created = new WecomContact(88L, "ww-corp", "wo-brand-new", 2L,
                null, null, null, null, null, null, null, null, null, null, null, "ACTIVE",
                1010L, "PENDING", null, "MANUAL", "企微侧边栏管家人工绑定客户", 2L, Instant.now(), 0L);
        when(wecomRepository.findContact(1L, "ww-corp", "wo-brand-new", 2L)).thenReturn(Optional.of(created));

        WecomSidebarLinkContactResponse resp = service.linkContact(steward,
                new WecomSidebarLinkContactRequest("wo-brand-new", 1010L, null));

        ArgumentCaptor<WecomContact> captor = ArgumentCaptor.forClass(WecomContact.class);
        verify(wecomRepository).insertContact(eq(1L), captor.capture());
        assertThat(captor.getValue().consentStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().linkType()).isEqualTo("MANUAL");
        assertThat(captor.getValue().customerId()).isEqualTo(1010L);
        assertThat(resp.contactId()).isEqualTo(88L);
        assertThat(resp.alreadyLinked()).isFalse();
        verify(auditService).record(eq(1L), eq(2L), eq("WECOM_SIDEBAR_CONTACT_LINK"),
                eq("WECOM_CONTACT"), eq("88"), eq(null), any(), eq("企微侧边栏管家人工绑定客户"));
    }

    @Test
    void linkContactRejectsOverwriteAndEnforcesCustomerScope() {
        WecomContact boundOther = contact(60L, "wo-bound", 2L, 9999L, "ACTIVE");
        when(wecomRepository.findContactsByExternalUserid(1L, "ww-corp", "wo-bound")).thenReturn(List.of(boundOther));

        assertThatThrownBy(() -> service.linkContact(steward,
                new WecomSidebarLinkContactRequest("wo-bound", 1010L, "改绑")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(wecomRepository, never()).linkContact(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyLong());

        when(scopeSupport.requireVisible(1L, 3030L, steward))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));
        assertThatThrownBy(() -> service.linkContact(steward,
                new WecomSidebarLinkContactRequest("wo-any", 3030L, "越权")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(wecomRepository, never()).findContactsByExternalUserid(1L, "ww-corp", "wo-any");
    }

    @Test
    void linkContactIsIdempotentWhenAlreadyBoundToSameCustomer() {
        WecomContact alreadyBound = contact(70L, "wo-same", 2L, 1010L, "ACTIVE");
        when(wecomRepository.findContactsByExternalUserid(1L, "ww-corp", "wo-same")).thenReturn(List.of(alreadyBound));
        when(wecomRepository.findContactForUpdate(1L, "ww-corp", "wo-same", 2L)).thenReturn(Optional.of(alreadyBound));

        WecomSidebarLinkContactResponse resp = service.linkContact(steward,
                new WecomSidebarLinkContactRequest("wo-same", 1010L, "重复提交"));

        assertThat(resp.alreadyLinked()).isTrue();
        assertThat(resp.customerId()).isEqualTo(1010L);
        verify(wecomRepository, never()).linkContact(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyLong());
        verify(auditService, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    private WecomContact contact(long id, String extId, long staffUserId, Long customerId, String followStatus) {
        return new WecomContact(id, "ww-corp", extId, staffUserId, null, null, null, null, null,
                null, null, null, null, null, null, followStatus, customerId, "GRANTED", null,
                customerId == null ? null : "MANUAL", customerId == null ? null : "历史绑定",
                staffUserId, Instant.now(), 0L);
    }

    private CustomerDetailResponse customerDetail(long id, String no, String displayName, String nickname,
                                                  String tier, String ownerName, String mobileMasked) {
        return new CustomerDetailResponse(id, no, displayName, nickname, mobileMasked, "SH", "ACTIVE", tier,
                "HIGH", 2L, ownerName, 1L, 0, Instant.now(), Instant.now(), 0L, null, null, null, null,
                null, null, null, null, null, null, List.of(), List.of(), false, List.of());
    }
}
