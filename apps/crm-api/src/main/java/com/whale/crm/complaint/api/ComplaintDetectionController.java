package com.whale.crm.complaint.api;

import com.whale.crm.complaint.api.dto.ComplaintDetectionResponse;
import com.whale.crm.complaint.api.dto.SourceDetectionRequest;
import com.whale.crm.complaint.application.ComplaintDetectionApplicationService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/complaint-detection")
@PreAuthorize("hasAuthority('complaint:write')")
public class ComplaintDetectionController {
    private final ComplaintDetectionApplicationService service;

    public ComplaintDetectionController(ComplaintDetectionApplicationService service) {
        this.service = service;
    }

    @PostMapping("/followups/{id}")
    public ApiResponse<ComplaintDetectionResponse> detectFollowup(
            @PathVariable @Positive long id, @Valid @RequestBody SourceDetectionRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.detectFollowup(SecuritySupport.requireCurrentUser(), id, body.version(),
                idempotencyKey), requestId(request));
    }

    @PostMapping("/wecom-messages/{id}")
    public ApiResponse<ComplaintDetectionResponse> detectWecom(
            @PathVariable @Positive long id, @Valid @RequestBody SourceDetectionRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.detectWecom(SecuritySupport.requireCurrentUser(), id, body.version(),
                idempotencyKey), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
