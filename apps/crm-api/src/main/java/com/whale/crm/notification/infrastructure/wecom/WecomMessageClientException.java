package com.whale.crm.notification.infrastructure.wecom;

import com.whale.crm.notification.domain.WecomSendResult;

final class WecomMessageClientException extends RuntimeException {
    private final WecomSendResult.Status status;

    WecomMessageClientException(WecomSendResult.Status status, String message) {
        super(message);
        this.status = status;
    }

    WecomSendResult.Status status() {
        return status;
    }
}
