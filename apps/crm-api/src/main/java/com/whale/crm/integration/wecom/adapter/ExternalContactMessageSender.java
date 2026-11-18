package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.notification.domain.WecomSendResult;

public interface ExternalContactMessageSender {
    WecomSendResult send(ExternalContactMessageCommand command);

    String mode();
}
