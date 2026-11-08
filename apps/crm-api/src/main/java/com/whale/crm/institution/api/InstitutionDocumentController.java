package com.whale.crm.institution.api;

import com.whale.crm.institution.api.dto.ChangeRequestResponse;
import com.whale.crm.institution.api.dto.CreateContractRequest;
import com.whale.crm.institution.api.dto.CreateQualificationRequest;
import com.whale.crm.institution.api.dto.CreateWorkflowRequest;
import com.whale.crm.institution.api.dto.QualificationResponse;
import com.whale.crm.institution.application.InstitutionDocumentService;
import com.whale.crm.institution.domain.InstitutionContract;
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
@RequestMapping("/api/v1")
public class InstitutionDocumentController extends InstitutionBaseController {
    private final InstitutionDocumentService documentService;

    public InstitutionDocumentController(InstitutionDocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/institution-contracts")
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<PageResponse<InstitutionContract>> contracts(
            @RequestParam long institutionId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 48) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(documentService.contracts(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), institutionId, status, page, size), requestId(request));
    }

    @PostMapping("/institution-contracts")
    @PreAuthorize("hasAuthority('institution:change_request')")
    public ResponseEntity<ApiResponse<InstitutionContract>> createContract(
            @Valid @RequestBody CreateContractRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        InstitutionContract created = documentService.createContract(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping("/institution-contracts/{id}")
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<InstitutionContract> contract(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(documentService.contract(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/institution-contracts/{id}/activation-requests")
    @PreAuthorize("hasAuthority('institution:change_request')")
    public ApiResponse<ChangeRequestResponse> activateContract(@PathVariable long id,
            @Valid @RequestBody CreateWorkflowRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(documentService.activateContract(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }

    @GetMapping("/institution-qualifications")
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<PageResponse<QualificationResponse>> qualifications(
            @RequestParam long institutionId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 48) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(documentService.qualifications(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), institutionId, status, page, size), requestId(request));
    }

    @PostMapping("/institution-qualifications")
    @PreAuthorize("hasAuthority('institution:change_request')")
    public ResponseEntity<ApiResponse<QualificationResponse>> createQualification(
            @Valid @RequestBody CreateQualificationRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        QualificationResponse created = documentService.createQualification(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping("/institution-qualifications/{id}")
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<QualificationResponse> qualification(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(documentService.qualification(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/institution-qualifications/{id}/review-requests")
    @PreAuthorize("hasAuthority('institution:change_request')")
    public ApiResponse<ChangeRequestResponse> reviewQualification(@PathVariable long id,
            @Valid @RequestBody CreateWorkflowRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(documentService.reviewQualification(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }
}
