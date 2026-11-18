package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.notification.domain.WecomSendResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class WecomExternalContactSenderRouter implements ExternalContactMessageSender {
    private final String configuredMode;
    private final ExternalContactMessageSender official;
    private final ExternalContactMessageSender mock;
    private final ExternalContactMessageSender disabled;

    public WecomExternalContactSenderRouter(
            @Value("${app.wecom.external-contact.mode:DISABLED}") String configuredMode,
            OfficialWecomExternalContactSender official, MockWecomExternalContactSender mock,
            DisabledWecomExternalContactSender disabled) {
        this.configuredMode = configuredMode;
        this.official = official;
        this.mock = mock;
        this.disabled = disabled;
    }

    @Override
    public WecomSendResult send(ExternalContactMessageCommand command) {
        return switch (mode()) {
            case "OFFICIAL" -> official.send(command);
            case "MOCK" -> mock.send(command);
            case "DISABLED" -> disabled.send(command);
            default -> new WecomSendResult(WecomSendResult.Status.FAILED, "DISABLED", null,
                    "WECOM_EXTERNAL_CONTACT_MODE_INVALID");
        };
    }

    @Override
    public String mode() {
        return configuredMode == null || configuredMode.isBlank() ? "DISABLED"
                : configuredMode.strip().toUpperCase(Locale.ROOT);
    }
}
