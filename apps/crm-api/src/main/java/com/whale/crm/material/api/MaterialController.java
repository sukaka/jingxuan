package com.whale.crm.material.api;

import com.whale.crm.material.api.dto.CreateMaterialRevisionRequest;
import com.whale.crm.material.api.dto.CreateMaterialRequest;
import com.whale.crm.material.api.dto.MaterialDownloadResponse;
import com.whale.crm.material.api.dto.MaterialResponse;
import com.whale.crm.material.api.dto.OfflineMaterialRequest;
import com.whale.crm.material.api.dto.ReviewMaterialRevisionRequest;
import com.whale.crm.material.api.dto.SubmitMaterialRevisionRequest;
import com.whale.crm.material.api.dto.UpdateMaterialRequest;
import com.whale.crm.material.application.MaterialApplicationService;
import com.whale.crm.material.domain.MaterialSearchCriteria;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/materials")
public class MaterialController {
    private final MaterialApplicationService service;

    public MaterialController(MaterialApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('material:read','material:review')")
    public ApiResponse<PageResponse<MaterialResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Size(max = 48) String categoryCode,
            @RequestParam(required = false) @Min(1) Long institutionId,
            @RequestParam(required = false) @Min(1) Long projectId,
            @RequestParam(required = false) @Size(max = 64) String cityCode,
            @RequestParam(required = false) @Pattern(regexp = "DRAFT|PENDING|APPROVED|REJECTED|OFFLINE") String status,
            @RequestParam(required = false) Boolean globalOnly,
            HttpServletRequest request) {
        MaterialSearchCriteria criteria = new MaterialSearchCriteria(keyword, categoryCode, institutionId, projectId,
                cityCode, status, globalOnly, page, size);
        return ApiResponse.ok(service.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                criteria), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('material:write')")
    public ResponseEntity<ApiResponse<MaterialResponse>> create(@Valid @RequestBody CreateMaterialRequest body,
                                                                 HttpServletRequest request) {
        MaterialResponse created = service.create(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('material:read','material:write','material:review')")
    public ApiResponse<MaterialResponse> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.detail(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id),
                requestId(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('material:write')")
    public ApiResponse<MaterialResponse> update(@PathVariable long id,
                                                 @Valid @RequestBody UpdateMaterialRequest body,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.update(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body), requestId(request));
    }

    @PostMapping("/{id}/revisions")
    @PreAuthorize("hasAuthority('material:write')")
    public ResponseEntity<ApiResponse<MaterialResponse>> createRevision(
            @PathVariable long id, @Valid @RequestBody CreateMaterialRevisionRequest body, HttpServletRequest request) {
        MaterialResponse created = service.createRevision(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PostMapping("/{id}/revisions/{revisionId}/submit")
    @PreAuthorize("hasAuthority('material:write')")
    public ApiResponse<MaterialResponse> submit(@PathVariable long id, @PathVariable long revisionId,
                                                 @Valid @RequestBody SubmitMaterialRevisionRequest body,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.submit(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, revisionId, body), requestId(request));
    }

    @PostMapping("/{id}/revisions/{revisionId}/review")
    @PreAuthorize("hasAuthority('material:review')")
    public ApiResponse<MaterialResponse> review(@PathVariable long id, @PathVariable long revisionId,
                                                 @Valid @RequestBody ReviewMaterialRevisionRequest body,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.review(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, revisionId, body), requestId(request));
    }

    @PostMapping("/{id}/offline")
    @PreAuthorize("hasAuthority('material:write')")
    public ApiResponse<MaterialResponse> offline(@PathVariable long id,
                                                 @Valid @RequestBody OfflineMaterialRequest body,
                                                 @RequestHeader(name = "Idempotency-Key")
                                                 @Size(min = 1, max = 128) String key,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.offline(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, key), requestId(request));
    }

    @GetMapping("/{id}/revisions/{revisionId}/download")
    @PreAuthorize("hasAnyAuthority('material:read','material:write','material:review')")
    public ApiResponse<MaterialDownloadResponse> download(@PathVariable long id, @PathVariable long revisionId,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(service.download(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, revisionId), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
