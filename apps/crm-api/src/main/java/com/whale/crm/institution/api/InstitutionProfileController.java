package com.whale.crm.institution.api;

import com.whale.crm.institution.api.dto.ChangeRequestResponse;
import com.whale.crm.institution.api.dto.CreateInstitutionProfileRequest;
import com.whale.crm.institution.api.dto.CreateWorkflowRequest;
import com.whale.crm.institution.api.dto.InstitutionProfileResponse;
import com.whale.crm.institution.api.dto.UpdateInstitutionProfileRequest;
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
@RequestMapping("/api/v1/institution-profiles")
public class InstitutionProfileController extends InstitutionBaseController {
    private final InstitutionProfileService profileService;

    public InstitutionProfileController(InstitutionProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<PageResponse<InstitutionProfileResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @Size(max = 120) String merchantKeyword,
            @RequestParam(required = false) @Size(max = 48) String status,
            @RequestParam(required = false) @Size(max = 64) String cityCode,
            HttpServletRequest request) {
        return ApiResponse.ok(profileService.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                keyword, merchantKeyword, status, cityCode, page, size), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('institution:write')")
    public ResponseEntity<ApiResponse<InstitutionProfileResponse>> create(
            @Valid @RequestBody CreateInstitutionProfileRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        InstitutionProfileResponse created = profileService.create(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<InstitutionProfileResponse> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(profileService.detail(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('institution:write')")
    public ApiResponse<InstitutionProfileResponse> update(@PathVariable long id,
            @Valid @RequestBody UpdateInstitutionProfileRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(profileService.update(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, key), requestId(request));
    }

    @PostMapping("/{id}/activation-requests")
    @PreAuthorize("hasAuthority('institution:change_request')")
    public ApiResponse<ChangeRequestResponse> activate(@PathVariable long id,
            @Valid @RequestBody CreateWorkflowRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(profileService.activationRequest(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }
}
