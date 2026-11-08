package com.whale.crm.platform.web;

import org.springframework.http.HttpStatus;

/**
 * 稳定错误码。新增领域错误码必须同步 contracts/errors/error-codes.md。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN),
    PENDING_BUSINESS_RULE(HttpStatus.UNPROCESSABLE_ENTITY),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    AUTH_PROTECTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    AI_PROVIDER_DISABLED(HttpStatus.SERVICE_UNAVAILABLE),
    AI_PROVIDER_FAILED(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
