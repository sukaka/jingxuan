package com.whale.crm.notification.infrastructure.wecom;

import com.whale.crm.notification.domain.WecomMessageClient;
import com.whale.crm.notification.domain.WecomMessageCommand;
import com.whale.crm.notification.domain.WecomSendResult;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class WecomMessageClientRouter implements WecomMessageClient {
    private final WecomMessageProperties properties;
    private final OfficialWecomMessageClient official;
    private final MockWecomMessageClient mock;
    private final DisabledWecomMessageClient disabled;

    public WecomMessageClientRouter(WecomMessageProperties properties, OfficialWecomMessageClient official,
                                    MockWecomMessageClient mock, DisabledWecomMessageClient disabled) {
        this.properties = properties;
        this.official = official;
        this.mock = mock;
        this.disabled = disabled;
    }

    @Override
    public WecomSendResult send(WecomMessageCommand command) {
        String mode = properties.mode() == null ? "DISABLED" : properties.mode().strip().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "OFFICIAL" -> official.send(command);
            case "MOCK" -> mock.send(command);
            case "DISABLED", "" -> disabled.send(command);
            default -> throw new IllegalStateException("Unsupported WeCom message mode");
        };
    }
}
