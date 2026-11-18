package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.adapter.DisabledWecomExternalContactSender;
import com.whale.crm.integration.wecom.adapter.ExternalContactMessageCommand;
import com.whale.crm.integration.wecom.adapter.MockWecomExternalContactSender;
import com.whale.crm.integration.wecom.adapter.OfficialWecomExternalContactSender;
import com.whale.crm.integration.wecom.adapter.WecomExternalContactSenderRouter;
import com.whale.crm.notification.domain.WecomSendResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomExternalContactSenderRouterTest {
    private final OfficialWecomExternalContactSender official = mock(OfficialWecomExternalContactSender.class);
    private final DisabledWecomExternalContactSender disabled = new DisabledWecomExternalContactSender();
    private final MockWecomExternalContactSender mockSender = new MockWecomExternalContactSender();
    private final ExternalContactMessageCommand command = new ExternalContactMessageCommand(
            1L, "corp", 2L, "external", "仅测试文本", "delegation-1");

    @Test
    void disabledIsDefaultAndDoesNotCallOfficial() {
        WecomExternalContactSenderRouter router = new WecomExternalContactSenderRouter(null, official, mockSender, disabled);

        WecomSendResult result = router.send(command);

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.DISABLED);
        assertThat(router.mode()).isEqualTo("DISABLED");
        verify(official, never()).send(any());
    }

    @Test
    void mockNeverClaimsCustomerDelivery() {
        WecomExternalContactSenderRouter router = new WecomExternalContactSenderRouter("MOCK", official, mockSender, disabled);

        WecomSendResult result = router.send(command);

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.MOCKED);
        assertThat(result.providerMessageId()).isNull();
        assertThat(result.errorMasked()).contains("未发送给客户");
        verify(official, never()).send(any());
    }

    @Test
    void officialUnknownDoesNotFallbackToMock() {
        when(official.send(command)).thenReturn(WecomSendResult.unknown("OFFICIAL", "RESPONSE_UNKNOWN"));
        when(official.mode()).thenReturn("OFFICIAL");
        WecomExternalContactSenderRouter router = new WecomExternalContactSenderRouter("OFFICIAL", official, mockSender, disabled);

        assertThat(router.send(command).status()).isEqualTo(WecomSendResult.Status.UNKNOWN);
        verify(official).send(command);
    }
}
