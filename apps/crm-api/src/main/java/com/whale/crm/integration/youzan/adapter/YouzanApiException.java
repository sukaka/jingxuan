package com.whale.crm.integration.youzan.adapter;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;

import java.time.Duration;

public class YouzanApiException extends BusinessException {
    private final int providerCode;
    private final boolean authError;
    private final boolean rateLimited;
    private final Duration retryAfter;

    public YouzanApiException(ErrorCode errorCode, String message, int providerCode,
                              boolean authError, boolean rateLimited, Duration retryAfter) {
        super(errorCode, message);
        this.providerCode = providerCode;
        this.authError = authError;
        this.rateLimited = rateLimited;
        this.retryAfter = retryAfter;
    }

    public int providerCode() {
        return providerCode;
    }

    public boolean authError() {
        return authError;
    }

    public boolean rateLimited() {
        return rateLimited;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
