package com.whale.crm.complaint.api;

import com.whale.crm.complaint.api.dto.ComplaintCaseDetailResponse;
import com.whale.crm.complaint.api.dto.ComplaintCaseResponse;
import com.whale.crm.complaint.api.dto.ComplaintHitDetailResponse;
import com.whale.crm.complaint.api.dto.ComplaintHitResponse;
import com.whale.crm.complaint.api.dto.ComplaintReasonRequest;
import com.whale.crm.complaint.api.dto.ResolveComplaintRequest;
import com.whale.crm.complaint.application.ComplaintWorkflowService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class ComplaintController {
    private final ComplaintWorkflowService service;

    public ComplaintController(ComplaintWorkflowService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/complaint-hits")
    @PreAuthorize("hasAuthority('complaint:read')")
    public ApiResponse<PageResponse<ComplaintHitResponse>> searchHits(
            @RequestParam(required = false) @Pattern(regexp = "OPEN|CONVERTED|DISMISSED") String status,
            @RequestParam(required = false) @Pattern(regexp = "ROUTED|EXCEPTION") String routingStatus,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(service.searchHits(SecuritySupport.requireCurrentUser(), status, routingStatus,
                keyword, page, size), requestId(request));
    }

    @GetMapping("/api/v1/complaint-hits/{id}")
    @PreAuthorize("hasAuthority('complaint:read')")
    public ApiResponse<ComplaintHitDetailResponse> getHit(@PathVariable @Positive long id,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(service.getHit(SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/api/v1/complaint-hits/{id}/confirm")
    @PreAuthorize("hasAuthority('complaint:write')")
    public ApiResponse<ComplaintCaseDetailResponse> confirmHit(
            @PathVariable @Positive long id, @Valid @RequestBody ComplaintReasonRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.confirmHit(SecuritySupport.requireCurrentUser(), id, body.version(),
                body.reason(), idempotencyKey), requestId(request));
    }

    @PostMapping("/api/v1/complaint-hits/{id}/dismiss")
    @PreAuthorize("hasAuthority('complaint:write')")
    public ApiResponse<ComplaintHitDetailResponse> dismissHit(
            @PathVariable @Positive long id, @Valid @RequestBody ComplaintReasonRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.dismissHit(SecuritySupport.requireCurrentUser(), id, body.version(),
                body.reason(), idempotencyKey), requestId(request));
    }

    @GetMapping("/api/v1/complaints")
    @PreAuthorize("hasAuthority('complaint:read')")
    public ApiResponse<PageResponse<ComplaintCaseResponse>> searchCases(
            @RequestParam(required = false) @Pattern(regexp = "OPEN|ASSIGNED|RESOLVED|CLOSED|DISMISSED") String status,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(service.searchCases(SecuritySupport.requireCurrentUser(), status, keyword, page, size),
                requestId(request));
    }

    @GetMapping("/api/v1/complaints/{id}")
    @PreAuthorize("hasAuthority('complaint:read')")
    public ApiResponse<ComplaintCaseDetailResponse> getCase(@PathVariable @Positive long id,
                                                             HttpServletRequest request) {
        return ApiResponse.ok(service.getCase(SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/api/v1/complaints/{id}/assign")
    @PreAuthorize("hasAuthority('complaint:write')")
    public ApiResponse<ComplaintCaseDetailResponse> assign(
            @PathVariable @Positive long id, @Valid @RequestBody ComplaintReasonRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.assign(SecuritySupport.requireCurrentUser(), id, body.version(),
                body.reason(), idempotencyKey), requestId(request));
    }

    @PostMapping("/api/v1/complaints/{id}/resolve")
    @PreAuthorize("hasAuthority('complaint:write')")
    public ApiResponse<ComplaintCaseDetailResponse> resolve(
            @PathVariable @Positive long id, @Valid @RequestBody ResolveComplaintRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.resolve(SecuritySupport.requireCurrentUser(), id, body.version(),
                body.resolution(), idempotencyKey), requestId(request));
    }

    @PostMapping("/api/v1/complaints/{id}/close")
    @PreAuthorize("hasAuthority('complaint:write')")
    public ApiResponse<ComplaintCaseDetailResponse> close(
            @PathVariable @Positive long id, @Valid @RequestBody ComplaintReasonRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.close(SecuritySupport.requireCurrentUser(), id, body.version(),
                body.reason(), idempotencyKey), requestId(request));
    }

    @PostMapping("/api/v1/complaints/{id}/dismiss")
    @PreAuthorize("hasAuthority('complaint:write')")
    public ApiResponse<ComplaintCaseDetailResponse> dismiss(
            @PathVariable @Positive long id, @Valid @RequestBody ComplaintReasonRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.dismiss(SecuritySupport.requireCurrentUser(), id, body.version(),
                body.reason(), idempotencyKey), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
