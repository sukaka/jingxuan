package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.notification.domain.WecomSendResult;
import org.springframework.stereotype.Component;

@Component
public class DisabledWecomExternalContactSender implements ExternalContactMessageSender {
    @Override
    public WecomSendResult send(ExternalContactMessageCommand command) {
        return new WecomSendResult(WecomSendResult.Status.DISABLED, "DISABLED", null,
                "WECOM_EXTERNAL_CONTACT_SENDER_DISABLED");
    }

    @Override
    public String mode() {
        return "DISABLED";
    }
}
