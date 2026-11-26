package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.application.WecomCallbackCryptoService;
import com.whale.crm.integration.wecom.application.WecomCallbackService;
import com.whale.crm.integration.wecom.application.WecomContactSyncService;
import com.whale.crm.integration.wecom.domain.WecomCallbackEvent;
import com.whale.crm.integration.wecom.infrastructure.mapper.WecomCallbackMapper;
import com.whale.crm.integration.wecom.tag.application.WecomTagSyncService;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomCallbackServiceTest {
    @Test
    void usesConfiguredAllowedTenantIdInsteadOfHardcodedTenantOne() {
        WecomCallbackMapper mapper = mock(WecomCallbackMapper.class);
        WecomOfficialProperties properties = new WecomOfficialProperties(7L, true, "corp-7", "secret",
                "https://qyapi.weixin.qq.com", "token", "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
                1000, 1000, 100, 10);
        WecomCallbackCryptoService crypto = new WecomCallbackCryptoService(properties);
        WecomContactSyncService contacts = mock(WecomContactSyncService.class);
        when(contacts.corpId(7L)).thenReturn("corp-7");
        WecomCallbackEvent pending = new WecomCallbackEvent(99L, "corp-7", "fp", "change_external_contact",
                "add_external_contact", "staff-7", "wm-ext-7", Instant.now(), "RECEIVED", 0);
        when(mapper.selectPending(eq(7L), any(), eq(50))).thenReturn(List.of(pending));
        when(mapper.claimWithLease(eq(7L), eq(99L), eq(0), any(), any())).thenReturn(1);

        WecomCallbackService service = new WecomCallbackService(mapper, crypto, contacts, properties);
        service.accept("<xml><CreateTime>1792990000</CreateTime><Event><![CDATA[change_external_contact]]></Event>"
                + "<ChangeType><![CDATA[add_external_contact]]></ChangeType><UserID><![CDATA[staff-7]]></UserID>"
                + "<ExternalUserID><![CDATA[wm-ext-7]]></ExternalUserID></xml>");
        service.processPending();

        verify(mapper).insert(eq(7L), any(), any());
        verify(mapper).recoverExpiredProcessing(eq(7L), any(), any(), eq(5), any());
        verify(contacts).refreshContact(7L, "wm-ext-7");
        verify(mapper).finishClaimed(eq(7L), eq(99L), eq(1), eq("SUCCEEDED"), any(), any(), any());
        verify(mapper, never()).insert(eq(1L), any(), any());
        verify(contacts, never()).refreshContact(eq(1L), any());
    }

    @Test
    void rejectsNonPositiveAllowedTenantIdAndSkipsPendingPolling() {
        WecomCallbackMapper mapper = mock(WecomCallbackMapper.class);
        WecomOfficialProperties invalid = new WecomOfficialProperties(0L, true, "corp", "secret",
                "https://qyapi.weixin.qq.com", "token", "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
                1000, 1000, 100, 10);
        WecomCallbackService service = new WecomCallbackService(mapper, new WecomCallbackCryptoService(invalid),
                mock(WecomContactSyncService.class), invalid);

        assertThatThrownBy(() -> service.accept("<xml><CreateTime>1792990000</CreateTime></xml>"))
                .isInstanceOf(BusinessException.class);
        service.processPending();
        verify(mapper, never()).selectPending(anyLong(), any(), anyInt());
    }

    @Test
    void handlesChangeExternalTagAndPullsCustomerTagsOnEditExternalContact() {
        WecomCallbackMapper mapper = mock(WecomCallbackMapper.class);
        WecomOfficialProperties properties = new WecomOfficialProperties(1L, true, "corp-1", "secret",
                "https://qyapi.weixin.qq.com", "token", "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
                1000, 1000, 100, 10);
        WecomCallbackCryptoService crypto = new WecomCallbackCryptoService(properties);
        WecomContactSyncService contacts = mock(WecomContactSyncService.class);
        WecomTagSyncService tagSync = mock(WecomTagSyncService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WecomTagSyncService> tagProvider = mock(ObjectProvider.class);
        when(tagProvider.getIfAvailable()).thenReturn(tagSync);

        WecomCallbackEvent tagEvent = new WecomCallbackEvent(101L, "corp-1", "fp-tag", "change_external_tag",
                "update", null, null, Instant.now(), "RECEIVED", 0);
        WecomCallbackEvent contactEvent = new WecomCallbackEvent(102L, "corp-1", "fp-contact",
                "change_external_contact", "edit_external_contact", "staff-2", "WECOM-EXT-1010",
                Instant.now(), "RECEIVED", 0);
        when(mapper.selectPending(eq(1L), any(), eq(50))).thenReturn(List.of(tagEvent, contactEvent));
        when(mapper.claimWithLease(eq(1L), eq(101L), eq(0), any(), any())).thenReturn(1);
        when(mapper.claimWithLease(eq(1L), eq(102L), eq(0), any(), any())).thenReturn(1);

        WecomCallbackService service = new WecomCallbackService(mapper, crypto, contacts, properties, tagProvider);
        service.processPending();

        verify(tagSync).handleTagCatalogCallback(1L, "update");
        verify(contacts).refreshContact(1L, "WECOM-EXT-1010");
        verify(tagSync, never()).pullCustomerTagsForExternalUser(anyLong(), anyString(), anyString());
        verify(mapper).finishClaimed(eq(1L), eq(101L), eq(1), eq("SUCCEEDED"), any(), any(), any());
        verify(mapper).finishClaimed(eq(1L), eq(102L), eq(1), eq("SUCCEEDED"), any(), any(), any());
    }
}
