package com.whale.crm.product.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.Institution;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/institutions")
public class InstitutionController {
    private final CatalogApplicationService service;

    public InstitutionController(CatalogApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<PageResponse<Institution>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(required = false) String status, HttpServletRequest request) {
        return ApiResponse.ok(service.institutions(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), keyword, status, page, size), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
