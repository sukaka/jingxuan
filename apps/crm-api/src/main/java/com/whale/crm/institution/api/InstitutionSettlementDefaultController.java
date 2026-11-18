package com.whale.crm.institution.api;

import com.whale.crm.institution.api.dto.InstitutionSettlementDefaultResponse;
import com.whale.crm.institution.api.dto.UpdateInstitutionSettlementDefaultRequest;
import com.whale.crm.institution.application.InstitutionSettlementDayService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/institution-settlement-default")
public class InstitutionSettlementDefaultController extends InstitutionBaseController {
    private final InstitutionSettlementDayService service;

    public InstitutionSettlementDefaultController(InstitutionSettlementDayService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('institution:read')")
    public ApiResponse<InstitutionSettlementDefaultResponse> get(HttpServletRequest request) {
        return ApiResponse.ok(service.get(TenantContext.currentTenantId()), requestId(request));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('institution:write')")
    public ApiResponse<InstitutionSettlementDefaultResponse> save(
            @Valid @RequestBody UpdateInstitutionSettlementDefaultRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.save(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                body, key), requestId(request));
    }
}
