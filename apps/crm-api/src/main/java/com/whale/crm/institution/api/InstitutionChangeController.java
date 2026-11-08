package com.whale.crm.institution.api;

import com.whale.crm.institution.api.dto.ChangeRequestResponse;
import com.whale.crm.institution.api.dto.CreateProfileChangeRequest;
import com.whale.crm.institution.api.dto.RevalidateChangeRequest;
import com.whale.crm.institution.api.dto.ReviewChangeRequest;
import com.whale.crm.institution.application.InstitutionChangeService;
import com.whale.crm.institution.application.InstitutionProfileService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/institution-change-requests")
public class InstitutionChangeController extends InstitutionBaseController {
    private final InstitutionChangeService changeService;
    private final InstitutionProfileService profileService;

    public InstitutionChangeController(InstitutionChangeService changeService, InstitutionProfileService profileService) {
        this.changeService = changeService;
        this.profileService = profileService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<PageResponse<ChangeRequestResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long institutionId,
            @RequestParam(required = false) @Size(max = 48) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(changeService.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                institutionId, status, page, size), requestId(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<ChangeRequestResponse> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(changeService.detail(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('institution:change_request')")
    public ResponseEntity<ApiResponse<ChangeRequestResponse>> create(
            @Valid @RequestBody CreateProfileChangeRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        ChangeRequestResponse created = profileService.fieldChange(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PostMapping("/{id}/market-review")
    @PreAuthorize("hasAuthority('institution:approve_market')")
    public ApiResponse<ChangeRequestResponse> market(@PathVariable long id, @Valid @RequestBody ReviewChangeRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(changeService.review(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, "MARKET", body, key), requestId(request));
    }

    @PostMapping("/{id}/finance-review")
    @PreAuthorize("hasAuthority('institution:approve_finance')")
    public ApiResponse<ChangeRequestResponse> finance(@PathVariable long id, @Valid @RequestBody ReviewChangeRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(changeService.review(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, "FINANCE", body, key), requestId(request));
    }

    @PostMapping("/{id}/revalidate")
    @PreAuthorize("hasAuthority('institution:change_request')")
    public ApiResponse<ChangeRequestResponse> revalidate(@PathVariable long id,
            @Valid @RequestBody RevalidateChangeRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(changeService.revalidate(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }
}
