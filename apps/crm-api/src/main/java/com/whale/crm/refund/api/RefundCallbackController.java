package com.whale.crm.refund.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.refund.api.dto.RefundCallbackRequest;
import com.whale.crm.refund.api.dto.RefundDetail;
import com.whale.crm.refund.application.RefundApplicationService;
import com.whale.crm.refund.application.RefundProviderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/integrations/refunds")
public class RefundCallbackController {
    private final RefundApplicationService service;
    private final RefundProviderAdapter adapter;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public RefundCallbackController(RefundApplicationService service, RefundProviderAdapter adapter,
                                    ObjectMapper objectMapper, Validator validator) {
        this.service = service;
        this.adapter = adapter;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping("/{provider}/callback")
    public ApiResponse<RefundDetail> callback(
            @PathVariable String provider, @RequestBody String rawBody,
            @RequestHeader(name = "X-Refund-Mock-Signature", required = false) String signature,
            @RequestHeader(name = "X-Refund-Mock-Timestamp", required = false) Long timestamp,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request) {
        if (!adapter.supports(provider) || !adapter.verify(rawBody, signature, timestamp)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "退款回调提供方未知、Mock未启用或签名无效/过期");
        }
        try {
            RefundCallbackRequest body = objectMapper.readValue(rawBody, RefundCallbackRequest.class);
            Set<ConstraintViolation<RefundCallbackRequest>> violations = validator.validate(body);
            if (!violations.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退款回调字段无效");
            }
            RefundDetail result = service.handleCallback(TenantContext.currentTenantId(), provider, body, idempotencyKey);
            Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
            return ApiResponse.ok(result, requestId instanceof String id ? id : null);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退款回调JSON格式或字段无效");
        }
    }
}
