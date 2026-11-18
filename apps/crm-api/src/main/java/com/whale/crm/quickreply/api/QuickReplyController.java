package com.whale.crm.quickreply.api;

import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.quickreply.api.dto.QuickReplyCategoryRequest;
import com.whale.crm.quickreply.api.dto.QuickReplyCategoryResponse;
import com.whale.crm.quickreply.api.dto.QuickReplyPreviewRequest;
import com.whale.crm.quickreply.api.dto.QuickReplyPreviewResponse;
import com.whale.crm.quickreply.api.dto.QuickReplyReasonRequest;
import com.whale.crm.quickreply.api.dto.QuickReplyTemplateRequest;
import com.whale.crm.quickreply.api.dto.QuickReplyTemplateResponse;
import com.whale.crm.quickreply.api.dto.QuickReplyUsageRequest;
import com.whale.crm.quickreply.api.dto.QuickReplyUsageResponse;
import com.whale.crm.quickreply.api.dto.QuickReplyVariableResponse;
import com.whale.crm.quickreply.application.QuickReplyAdminService;
import com.whale.crm.quickreply.application.QuickReplyEngagementService;
import com.whale.crm.quickreply.application.QuickReplyPreviewService;
import com.whale.crm.quickreply.application.QuickReplyUsageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/quick-replies")
public class QuickReplyController {
    private final QuickReplyPreviewService previewService;
    private final QuickReplyAdminService adminService;
    private final QuickReplyUsageService usageService;
    private final QuickReplyEngagementService engagementService;

    public QuickReplyController(QuickReplyPreviewService previewService, QuickReplyAdminService adminService,
                                QuickReplyUsageService usageService, QuickReplyEngagementService engagementService) {
        this.previewService = previewService;
        this.adminService = adminService;
        this.usageService = usageService;
        this.engagementService = engagementService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('quickreply:view')")
    public ApiResponse<List<QuickReplyTemplateResponse>> list(@RequestParam(required = false) @Size(max = 64) String scene,
                                                               HttpServletRequest request) {
        return ApiResponse.ok(previewService.list(SecuritySupport.requireCurrentUser(), scene), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('quickreply:manage')")
    public ResponseEntity<ApiResponse<QuickReplyTemplateResponse>> createTemplate(
            @Valid @RequestBody QuickReplyTemplateRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        QuickReplyTemplateResponse response = adminService.createTemplate(SecuritySupport.requireCurrentUser(), body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response, requestId(request)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('quickreply:manage')")
    public ApiResponse<QuickReplyTemplateResponse> updateTemplate(@PathVariable @Positive long id,
            @Valid @RequestBody QuickReplyTemplateRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        return ApiResponse.ok(adminService.updateTemplate(SecuritySupport.requireCurrentUser(), id, body, key),
                requestId(request));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('quickreply:manage')")
    public ApiResponse<QuickReplyTemplateResponse> disableTemplate(@PathVariable @Positive long id,
            @Valid @RequestBody QuickReplyReasonRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        return ApiResponse.ok(adminService.disableTemplate(SecuritySupport.requireCurrentUser(), id, body, key),
                requestId(request));
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('quickreply:view')")
    public ApiResponse<List<QuickReplyCategoryResponse>> categories(HttpServletRequest request) {
        return ApiResponse.ok(previewService.categories(SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAuthority('quickreply:manage')")
    public ResponseEntity<ApiResponse<QuickReplyCategoryResponse>> createCategory(
            @Valid @RequestBody QuickReplyCategoryRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        QuickReplyCategoryResponse response = adminService.createCategory(SecuritySupport.requireCurrentUser(), body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response, requestId(request)));
    }

    @PatchMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('quickreply:manage')")
    public ApiResponse<QuickReplyCategoryResponse> updateCategory(@PathVariable @Positive long id,
            @Valid @RequestBody QuickReplyCategoryRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        return ApiResponse.ok(adminService.updateCategory(SecuritySupport.requireCurrentUser(), id, body, key),
                requestId(request));
    }

    @PostMapping("/categories/{id}/disable")
    @PreAuthorize("hasAuthority('quickreply:manage')")
    public ApiResponse<QuickReplyCategoryResponse> disableCategory(@PathVariable @Positive long id,
            @Valid @RequestBody QuickReplyReasonRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        return ApiResponse.ok(adminService.disableCategory(SecuritySupport.requireCurrentUser(), id, body, key),
                requestId(request));
    }

    @GetMapping("/variables")
    @PreAuthorize("hasAuthority('quickreply:view')")
    public ApiResponse<List<QuickReplyVariableResponse>> variables(HttpServletRequest request) {
        return ApiResponse.ok(previewService.variables(SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @PostMapping("/{id}/preview")
    @PreAuthorize("hasAuthority('quickreply:view')")
    public ApiResponse<QuickReplyPreviewResponse> preview(@PathVariable @Positive long id,
            @Valid @RequestBody QuickReplyPreviewRequest body, HttpServletRequest request) {
        return ApiResponse.ok(previewService.preview(SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PostMapping("/{id}/usage")
    @PreAuthorize("hasAuthority('quickreply:view')")
    public ResponseEntity<ApiResponse<QuickReplyUsageResponse>> recordUsage(@PathVariable @Positive long id,
            @Valid @RequestBody QuickReplyUsageRequest body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        QuickReplyUsageResponse response = usageService.record(SecuritySupport.requireCurrentUser(), id, body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response, requestId(request)));
    }

    @GetMapping("/usages")
    @PreAuthorize("hasAuthority('quickreply:view')")
    public ApiResponse<PageResponse<QuickReplyUsageResponse>> usages(
            @RequestParam(required = false) @Positive Long templateId,
            @RequestParam(required = false) @Positive Long employeeId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(usageService.search(SecuritySupport.requireCurrentUser(), templateId, employeeId,
                page, size), requestId(request));
    }

    @PutMapping("/{id}/favorite")
    @PreAuthorize("hasAuthority('quickreply:view')")
    public ApiResponse<QuickReplyTemplateResponse> favorite(@PathVariable @Positive long id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        return ApiResponse.ok(engagementService.setFavorite(SecuritySupport.requireCurrentUser(), id, true, key),
                requestId(request));
    }

    @DeleteMapping("/{id}/favorite")
    @PreAuthorize("hasAuthority('quickreply:view')")
    public ApiResponse<QuickReplyTemplateResponse> unfavorite(@PathVariable @Positive long id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        return ApiResponse.ok(engagementService.setFavorite(SecuritySupport.requireCurrentUser(), id, false, key),
                requestId(request));
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasAuthority('quickreply:view')")
    public ApiResponse<java.util.List<com.whale.crm.quickreply.api.dto.QuickReplyUsageStatResponse>> statistics(
            @RequestParam(required = false) @Positive Long employeeId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            HttpServletRequest request) {
        return ApiResponse.ok(engagementService.statistics(SecuritySupport.requireCurrentUser(), employeeId, from, to),
                requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
