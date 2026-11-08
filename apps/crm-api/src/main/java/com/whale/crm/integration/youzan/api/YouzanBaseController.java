package com.whale.crm.integration.youzan.api;

import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;

public abstract class YouzanBaseController {
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> missingHeader(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_FAILED.name(),
                "写请求必须提供Idempotency-Key", null, requestId(request)));
    }

    protected String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
