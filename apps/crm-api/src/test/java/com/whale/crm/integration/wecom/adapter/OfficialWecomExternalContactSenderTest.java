package com.whale.crm.integration.wecom.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRepository;
import com.whale.crm.integration.wecom.domain.WecomStaffAccount;
import com.whale.crm.notification.domain.WecomSendResult;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OfficialWecomExternalContactSenderTest {
    private final WecomTokenService tokens = mock(WecomTokenService.class);
    private final WecomContactSyncRepository contacts = mock(WecomContactSyncRepository.class);
    private final IntegrationRuntimeConfigService runtime = mock(IntegrationRuntimeConfigService.class);
    private final OkHttpClient http = mock(OkHttpClient.class);

    @Test
    void ambiguousStaffMappingFailsBeforeTokenOrNetworkCall() {
        when(runtime.wecomOfficialEnabled(1L)).thenReturn(true);
        when(contacts.staffByUserId(1L, "corp", 8L)).thenReturn(List.of(
                new WecomStaffAccount(1L, "corp", "staff-a", 8L, true),
                new WecomStaffAccount(2L, "corp", "staff-b", 8L, true)));
        OfficialWecomExternalContactSender sender = new OfficialWecomExternalContactSender(true,
                properties(true), tokens, contacts, runtime, new ObjectMapper(), http);

        WecomSendResult result = sender.send(new ExternalContactMessageCommand(1L, "corp", 8L,
                "external", "正常回复", "run-1"));

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.FAILED);
        assertThat(result.errorMasked()).isEqualTo("WECOM_EXTERNAL_CONTACT_STAFF_UNMAPPED");
        verifyNoInteractions(tokens, http);
    }

    @Test
    void officialCapabilityFlagOffCannotMakeNetworkCall() {
        OfficialWecomExternalContactSender sender = new OfficialWecomExternalContactSender(false,
                properties(true), tokens, contacts, runtime, new ObjectMapper(), http);

        WecomSendResult result = sender.send(new ExternalContactMessageCommand(1L, "corp", 8L,
                "external", "正常回复", "run-1"));

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.FAILED);
        assertThat(result.errorMasked()).isEqualTo("WECOM_EXTERNAL_CONTACT_OFFICIAL_DISABLED");
        verifyNoInteractions(tokens, contacts, http);
    }

    @Test
    void tenantRuntimeSwitchOffWinsOverStaticConfigAndMakesNoHttpCall() {
        when(runtime.wecomOfficialEnabled(1L)).thenReturn(false);
        OfficialWecomExternalContactSender sender = new OfficialWecomExternalContactSender(true,
                properties(true), tokens, contacts, runtime, new ObjectMapper(), http);

        WecomSendResult result = sender.send(new ExternalContactMessageCommand(1L, "corp", 8L,
                "external", "正常回复", "run-1"));

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.FAILED);
        assertThat(result.errorMasked()).isEqualTo("WECOM_EXTERNAL_CONTACT_OFFICIAL_DISABLED");
        verifyNoInteractions(tokens, contacts, http);
    }

    @Test
    void actualIoFailureIsUnknownRatherThanRetryable() throws Exception {
        when(runtime.wecomOfficialEnabled(1L)).thenReturn(true);
        when(contacts.staffByUserId(1L, "corp", 8L)).thenReturn(List.of(
                new WecomStaffAccount(1L, "corp", "staff-a", 8L, true)));
        when(tokens.token()).thenReturn("synthetic-token");
        okhttp3.Call call = mock(okhttp3.Call.class);
        when(http.newCall(org.mockito.ArgumentMatchers.any())).thenReturn(call);
        when(call.execute()).thenThrow(new java.io.IOException("synthetic-io"));
        OfficialWecomExternalContactSender sender = new OfficialWecomExternalContactSender(true,
                properties(true), tokens, contacts, runtime, new ObjectMapper(), http);

        WecomSendResult result = sender.send(new ExternalContactMessageCommand(1L, "corp", 8L,
                "external", "正常回复", "run-1"));

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.UNKNOWN);
        assertThat(result.errorMasked()).isEqualTo("WECOM_EXTERNAL_CONTACT_RESPONSE_UNKNOWN");
    }

    private WecomOfficialProperties properties(boolean enabled) {
        return new WecomOfficialProperties(enabled, "corp", "synthetic-secret",
                "https://qyapi.weixin.qq.com", null, null, 100, 100, 100, 1);
    }
}
