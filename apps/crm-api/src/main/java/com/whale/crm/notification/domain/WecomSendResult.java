package com.whale.crm.notification.domain;

public record WecomSendResult(Status status, String provider, String providerMessageId, String errorMasked) {
    public enum Status {
        SENT,
        MOCKED,
        DISABLED,
        RETRYABLE,
        FAILED,
        UNKNOWN
    }

    public static WecomSendResult unknown(String provider, String errorMasked) {
        return new WecomSendResult(Status.UNKNOWN, provider, null, errorMasked);
    }
}
