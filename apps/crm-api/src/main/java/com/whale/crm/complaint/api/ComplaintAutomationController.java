package com.whale.crm.complaint.api;

import com.whale.crm.complaint.api.dto.ComplaintEmpathyScriptResponse;
import com.whale.crm.complaint.api.dto.ComplaintKeywordResponse;
import com.whale.crm.complaint.api.dto.ComplaintPolicyResponse;
import com.whale.crm.complaint.api.dto.ComplaintReasonRequest;
import com.whale.crm.complaint.api.dto.CreateComplaintEmpathyScriptRequest;
import com.whale.crm.complaint.api.dto.CreateComplaintKeywordRequest;
import com.whale.crm.complaint.api.dto.UpdateComplaintEmpathyScriptRequest;
import com.whale.crm.complaint.api.dto.UpdateComplaintKeywordRequest;
import com.whale.crm.complaint.api.dto.UpdateComplaintPolicyRequest;
import com.whale.crm.complaint.application.ComplaintEmpathyScriptService;
import com.whale.crm.complaint.application.ComplaintSettingsService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/complaint-automation")
@PreAuthorize("hasAuthority('complaint:config')")
public class ComplaintAutomationController {
    private final ComplaintSettingsService service;
    private final ComplaintEmpathyScriptService empathyScriptService;

    public ComplaintAutomationController(ComplaintSettingsService service,
                                         ComplaintEmpathyScriptService empathyScriptService) {
        this.service = service;
        this.empathyScriptService = empathyScriptService;
    }

    @GetMapping("/settings")
    public ApiResponse<ComplaintPolicyResponse> getPolicy(HttpServletRequest request) {
        return ApiResponse.ok(service.getPolicy(SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @PatchMapping("/settings")
    public ApiResponse<ComplaintPolicyResponse> updatePolicy(
            @Valid @RequestBody UpdateComplaintPolicyRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.updatePolicy(SecuritySupport.requireCurrentUser(), body, idempotencyKey),
                requestId(request));
    }

    @GetMapping("/keywords")
    public ApiResponse<List<ComplaintKeywordResponse>> listKeywords(HttpServletRequest request) {
        return ApiResponse.ok(service.listKeywords(SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @PostMapping("/keywords")
    public ResponseEntity<ApiResponse<ComplaintKeywordResponse>> createKeyword(
            @Valid @RequestBody CreateComplaintKeywordRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        ComplaintKeywordResponse response = service.createKeyword(
                SecuritySupport.requireCurrentUser(), body, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response, requestId(request)));
    }

    @PatchMapping("/keywords/{id}")
    public ApiResponse<ComplaintKeywordResponse> updateKeyword(
            @PathVariable @Positive long id, @Valid @RequestBody UpdateComplaintKeywordRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.updateKeyword(SecuritySupport.requireCurrentUser(), id, body, idempotencyKey),
                requestId(request));
    }

    @PostMapping("/keywords/{id}/disable")
    public ApiResponse<ComplaintKeywordResponse> disableKeyword(
            @PathVariable @Positive long id, @Valid @RequestBody ComplaintReasonRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.disableKeyword(SecuritySupport.requireCurrentUser(), id, body.version(),
                body.reason(), idempotencyKey), requestId(request));
    }

    @GetMapping("/empathy-scripts")
    public ApiResponse<List<ComplaintEmpathyScriptResponse>> listEmpathyScripts(HttpServletRequest request) {
        return ApiResponse.ok(empathyScriptService.list(SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @PostMapping("/empathy-scripts")
    public ResponseEntity<ApiResponse<ComplaintEmpathyScriptResponse>> createEmpathyScript(
            @Valid @RequestBody CreateComplaintEmpathyScriptRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        ComplaintEmpathyScriptResponse response = empathyScriptService.create(
                SecuritySupport.requireCurrentUser(), body, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response, requestId(request)));
    }

    @PatchMapping("/empathy-scripts/{id}")
    public ApiResponse<ComplaintEmpathyScriptResponse> updateEmpathyScript(
            @PathVariable @Positive long id, @Valid @RequestBody UpdateComplaintEmpathyScriptRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(empathyScriptService.update(
                SecuritySupport.requireCurrentUser(), id, body, idempotencyKey), requestId(request));
    }

    @PostMapping("/empathy-scripts/{id}/disable")
    public ApiResponse<ComplaintEmpathyScriptResponse> disableEmpathyScript(
            @PathVariable @Positive long id, @Valid @RequestBody ComplaintReasonRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(empathyScriptService.disable(SecuritySupport.requireCurrentUser(), id, body.version(),
                body.reason(), idempotencyKey), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
