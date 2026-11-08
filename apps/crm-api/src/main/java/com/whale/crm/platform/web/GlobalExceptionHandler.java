package com.whale.crm.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 统一异常 → 稳定错误码 + requestId。500 不回传内部细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException ex, HttpServletRequest request) {
        if (ex.errorCode() == ErrorCode.RATE_LIMITED && request.getRequestURI().endsWith("/auth/login")) {
            return ResponseEntity.status(ex.errorCode().httpStatus()).header("Retry-After", "900")
                    .body(ApiResponse.error(ex.errorCode().name(), ex.getMessage(), ex.details(), requestId(request)));
        }
        return respond(ex.errorCode().httpStatus(), ex.errorCode().name(), ex.getMessage(), ex.details(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiResponse.ErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toDetail)
                .toList();
        return badRequest("请求参数校验失败", details, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> constraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiResponse.ErrorDetail> details = ex.getConstraintViolations().stream()
                .map(v -> new ApiResponse.ErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return badRequest("请求参数校验失败", details, request);
    }

    @ExceptionHandler({
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Void>> badRequestTypes(Exception ex, HttpServletRequest request) {
        return badRequest("请求参数无效", null, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND.name(), "资源不存在或不可见", null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND.name(), "资源不存在或不可见", null, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> accessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN.name(), "没有访问该资源的权限", null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception ex, HttpServletRequest request) {
        log.error("未处理异常 requestId={}", requestId(request), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(), "系统内部错误", null, request);
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message, List<ApiResponse.ErrorDetail> details, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.name(), message, details, request);
    }

    private ResponseEntity<ApiResponse<Void>> respond(HttpStatus status, String code, String message,
                                                      List<ApiResponse.ErrorDetail> details, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiResponse.error(code, message, details, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }

    private ApiResponse.ErrorDetail toDetail(FieldError error) {
        return new ApiResponse.ErrorDetail(error.getField(), error.getDefaultMessage());
    }
}
