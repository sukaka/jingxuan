package com.whale.crm.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 统一 API 响应信封。成功：code=OK + data；失败：业务 code + message + details。
 * 契约见 contracts/errors/error-codes.md 与 docs/04_接口与集成/40_REST_API规范.md。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        List<ErrorDetail> details,
        String requestId,
        Instant timestamp) {
    public ApiResponse {
        details = details == null ? null : List.copyOf(details);
    }

    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>("OK", "success", data, null, requestId, Instant.now());
    }

    public static ApiResponse<Void> error(String code, String message, List<ErrorDetail> details, String requestId) {
        return new ApiResponse<>(code, message, null, details, requestId, Instant.now());
    }

    public record ErrorDetail(String field, String reason) {
    }
}
