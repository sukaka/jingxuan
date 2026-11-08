package com.whale.crm.product.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.product.api.dto.CreateSkuRequest;
import com.whale.crm.product.api.dto.SkuReviewRequest;
import com.whale.crm.product.api.dto.UpdateSkuRequest;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.Sku;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/skus")
public class SkuController {
    private final CatalogApplicationService service;

    public SkuController(CatalogApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('product:read')")
    public ApiResponse<PageResponse<Sku>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long packageId, HttpServletRequest request) {
        return ApiResponse.ok(service.skus(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), keyword, status, packageId, page, size), requestId(request));
    }

    @GetMapping("/{id}/institutions")
    @PreAuthorize("hasAuthority('product:read')")
    public ApiResponse<java.util.List<com.whale.crm.product.domain.Institution>> institutions(
            @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.skuInstitutions(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:write')")
    public ResponseEntity<ApiResponse<Sku>> create(@Valid @RequestBody CreateSkuRequest body, HttpServletRequest request) {
        Sku created = service.createSku(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('product:write')")
    public ApiResponse<Sku> update(@PathVariable long id, @Valid @RequestBody UpdateSkuRequest body,
                                   HttpServletRequest request) {
        return ApiResponse.ok(service.updateSku(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('product:write')")
    public ApiResponse<Sku> activate(@PathVariable long id, @Valid @RequestBody SkuReviewRequest body,
                                     HttpServletRequest request) {
        return ApiResponse.ok(service.activateSku(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body.version()), requestId(request));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('product:write')")
    public ApiResponse<Sku> disable(@PathVariable long id, @Valid @RequestBody SkuReviewRequest body,
                                    HttpServletRequest request) {
        return ApiResponse.ok(service.disableSku(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body.version()), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
