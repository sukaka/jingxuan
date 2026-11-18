package com.whale.crm.notification.infrastructure.wecom;

import com.whale.crm.notification.domain.WecomMessageClient;
import com.whale.crm.notification.domain.WecomMessageCommand;
import com.whale.crm.notification.domain.WecomSendResult;
import org.springframework.stereotype.Component;

@Component
public class DisabledWecomMessageClient implements WecomMessageClient {
    @Override
    public WecomSendResult send(WecomMessageCommand command) {
        return new WecomSendResult(WecomSendResult.Status.DISABLED, "DISABLED", null,
                "WECOM_MESSAGE_DISABLED");
    }
}
