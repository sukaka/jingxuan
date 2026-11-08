package com.whale.crm.settlement.api;

import com.whale.crm.institution.api.InstitutionBaseController;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.settlement.api.dto.ReconciliationResponse;
import com.whale.crm.settlement.application.ReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settlements/reconciliation")
public class ReconciliationController extends InstitutionBaseController {
    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settlement:read')")
    public ApiResponse<ReconciliationResponse> reconcile(@RequestParam(required = false) Long institutionId,
                                                         HttpServletRequest request) {
        return ApiResponse.ok(service.reconcile(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                institutionId), requestId(request));
    }
}
