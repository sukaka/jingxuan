package com.whale.crm.product.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.product.api.dto.CreatePackageRequest;
import com.whale.crm.product.api.dto.CreatePackageVersionRequest;
import com.whale.crm.product.api.dto.ReplaceVersionProjectsRequest;
import com.whale.crm.product.api.dto.UpdatePackageRequest;
import com.whale.crm.product.api.dto.UpdatePackageVersionRequest;
import com.whale.crm.product.api.dto.VersionReviewRequest;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.PackageVersion;
import com.whale.crm.product.domain.PackageVersionSnapshot;
import com.whale.crm.product.domain.ProductPackage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1")
public class PackageController {
    private final CatalogApplicationService service;

    public PackageController(CatalogApplicationService service) {
        this.service = service;
    }

    @GetMapping("/packages")
    @PreAuthorize("hasAuthority('product:read')")
    public ApiResponse<PageResponse<ProductPackage>> packages(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(required = false) String status, HttpServletRequest request) {
        return ApiResponse.ok(service.packages(TenantContext.currentTenantId(), keyword, status, page, size), requestId(request));
    }

    @PostMapping("/packages")
    @PreAuthorize("hasAuthority('product:write')")
    public ResponseEntity<ApiResponse<ProductPackage>> createPackage(@Valid @RequestBody CreatePackageRequest body,
                                                                     HttpServletRequest request) {
        ProductPackage created = service.createPackage(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/packages/{id}")
    @PreAuthorize("hasAuthority('product:write')")
    public ApiResponse<ProductPackage> updatePackage(@PathVariable long id, @Valid @RequestBody UpdatePackageRequest body,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(service.updatePackage(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @GetMapping("/packages/{id}/versions")
    @PreAuthorize("hasAuthority('product:read')")
    public ApiResponse<List<PackageVersion>> versions(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.versions(TenantContext.currentTenantId(), id), requestId(request));
    }

    @PostMapping("/packages/{id}/versions")
    @PreAuthorize("hasAuthority('product:write')")
    public ResponseEntity<ApiResponse<PackageVersionSnapshot>> createVersion(
            @PathVariable long id, @Valid @RequestBody CreatePackageVersionRequest body, HttpServletRequest request) {
        PackageVersionSnapshot created = service.createVersion(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping("/package-versions/{id}/snapshot")
    @PreAuthorize("hasAuthority('product:read')")
    public ApiResponse<PackageVersionSnapshot> snapshot(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.snapshot(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PatchMapping("/package-versions/{id}")
    @PreAuthorize("hasAuthority('product:write')")
    public ApiResponse<PackageVersionSnapshot> updateVersion(@PathVariable long id,
            @Valid @RequestBody UpdatePackageVersionRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.updateVersion(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PutMapping("/package-versions/{id}/projects")
    @PreAuthorize("hasAuthority('product:write')")
    public ApiResponse<PackageVersionSnapshot> replaceProjects(@PathVariable long id,
            @Valid @RequestBody ReplaceVersionProjectsRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.replaceProjects(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PostMapping("/package-versions/{id}/activate")
    @PreAuthorize("hasAuthority('product:write')")
    public ApiResponse<PackageVersionSnapshot> activateVersion(@PathVariable long id,
            @Valid @RequestBody VersionReviewRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.activateVersion(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body.version()), requestId(request));
    }

    @PostMapping("/package-versions/{id}/archive")
    @PreAuthorize("hasAuthority('product:write')")
    public ApiResponse<PackageVersionSnapshot> archiveVersion(@PathVariable long id,
            @Valid @RequestBody VersionReviewRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.archiveVersion(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body.version()), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
