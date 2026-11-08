package com.whale.crm.institution.api;

import com.whale.crm.institution.api.dto.CreateInstitutionMerchantResourceRequest;
import com.whale.crm.institution.api.dto.UpdateInstitutionMerchantResourceRequest;
import com.whale.crm.institution.application.InstitutionMerchantResourceService;
import com.whale.crm.institution.domain.InstitutionMerchantResource;
import com.whale.crm.institution.domain.InstitutionMerchantResourceSummary;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1")
public class InstitutionMerchantResourceController extends InstitutionBaseController {
    private final InstitutionMerchantResourceService service;

    public InstitutionMerchantResourceController(InstitutionMerchantResourceService service) {
        this.service = service;
    }

    @GetMapping("/institution-merchant-resources/summary")
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<List<InstitutionMerchantResourceSummary>> summary(HttpServletRequest request) {
        return ApiResponse.ok(service.summary(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @GetMapping("/institution-merchant-resources")
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<PageResponse<InstitutionMerchantResource>> search(
            @RequestParam(required = false) @Positive Long institutionId,
            @RequestParam(required = false) @Size(max = 32) String resourceType,
            @RequestParam(defaultValue = "PUBLISHED") @Size(max = 32) String status,
            @RequestParam(required = false) @Size(max = 120) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(service.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                institutionId, resourceType, status, keyword, page, size), requestId(request));
    }

    @PostMapping("/institution-profiles/{institutionId}/merchant-resources")
    @PreAuthorize("hasAuthority('institution:write')")
    public ResponseEntity<ApiResponse<InstitutionMerchantResource>> create(
            @PathVariable @Positive long institutionId,
            @Valid @RequestBody CreateInstitutionMerchantResourceRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        InstitutionMerchantResource created = service.create(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), institutionId, body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/institution-merchant-resources/{id}")
    @PreAuthorize("hasAuthority('institution:write')")
    public ApiResponse<InstitutionMerchantResource> update(
            @PathVariable @Positive long id,
            @Valid @RequestBody UpdateInstitutionMerchantResourceRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.update(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, key), requestId(request));
    }
}
