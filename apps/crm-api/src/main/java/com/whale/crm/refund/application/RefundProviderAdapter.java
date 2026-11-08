package com.whale.crm.refund.application;

public interface RefundProviderAdapter {
    boolean supports(String provider);

    boolean verify(String rawBody, String signature, Long timestamp);
}
