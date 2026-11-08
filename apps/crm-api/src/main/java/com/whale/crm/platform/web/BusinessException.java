package com.whale.crm.platform.web;

import java.util.List;

/**
 * 业务异常：携带稳定错误码与结构化 details。领域/应用层抛出，由 GlobalExceptionHandler 统一转换。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ApiResponse.ErrorDetail> details;

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, List<ApiResponse.ErrorDetail> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? null : List.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<ApiResponse.ErrorDetail> details() {
        return details;
    }
}
