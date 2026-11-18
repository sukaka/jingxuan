package com.whale.crm.notification.domain;

public interface WecomMessageClient {
    WecomSendResult send(WecomMessageCommand command);
}
