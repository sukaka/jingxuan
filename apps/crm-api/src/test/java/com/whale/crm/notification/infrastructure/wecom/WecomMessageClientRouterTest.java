package com.whale.crm.notification.infrastructure.wecom;

import com.whale.crm.notification.domain.WecomMessageCommand;
import com.whale.crm.notification.domain.WecomSendResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomMessageClientRouterTest {
    private final OfficialWecomMessageClient official = mock(OfficialWecomMessageClient.class);
    private final MockWecomMessageClient mock = new MockWecomMessageClient();
    private final DisabledWecomMessageClient disabled = new DisabledWecomMessageClient();
    private final WecomMessageCommand command = new WecomMessageCommand(1L, 2L, "staff-2", "标题", "脱敏内容");

    @Test
    void disabledIsDefaultSafeBehaviorAndNeverCallsOfficial() {
        WecomMessageClientRouter router = new WecomMessageClientRouter(properties("DISABLED"), official, mock, disabled);

        WecomSendResult result = router.send(command);

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.DISABLED);
        verify(official, never()).send(any());
    }

    @Test
    void mockIsExplicitlyMarkedAndNeverCallsOfficial() {
        WecomMessageClientRouter router = new WecomMessageClientRouter(properties("MOCK"), official, mock, disabled);

        WecomSendResult result = router.send(command);

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.MOCKED);
        assertThat(result.provider()).isEqualTo("MOCK");
        assertThat(result.providerMessageId()).startsWith("mock-");
        verify(official, never()).send(any());
    }

    @Test
    void officialModeDoesNotSilentlyFallbackToMock() {
        when(official.send(command)).thenReturn(WecomSendResult.unknown("OFFICIAL", "NETWORK_TIMEOUT"));
        WecomMessageClientRouter router = new WecomMessageClientRouter(properties("OFFICIAL"), official, mock, disabled);

        WecomSendResult result = router.send(command);

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.UNKNOWN);
        verify(official).send(command);
    }

    @Test
    void officialModeRejectsDisallowedTenantBeforeCallingOfficialClient() {
        WecomMessageClientRouter router = new WecomMessageClientRouter(properties("OFFICIAL"), official, mock, disabled);
        WecomMessageCommand otherTenant = new WecomMessageCommand(2L, 2L, "staff-2", "标题", "脱敏内容");

        WecomSendResult result = router.send(otherTenant);

        assertThat(result.status()).isEqualTo(WecomSendResult.Status.FAILED);
        assertThat(result.errorMasked()).isEqualTo("WECOM_MESSAGE_TENANT_FORBIDDEN");
        verify(official, never()).send(any());
    }

    private WecomMessageProperties properties(String mode) {
        return new WecomMessageProperties(1L, mode, "ww-test", "synthetic-secret", 1001L,
                "http://127.0.0.1:9", 100, 100, 60);
    }
}
