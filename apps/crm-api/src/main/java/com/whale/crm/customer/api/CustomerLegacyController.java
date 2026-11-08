package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.LegacyProfileItemResponse;
import com.whale.crm.customer.application.LegacyProfileApplicationService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerLegacyController {

    private final LegacyProfileApplicationService legacyProfileService;

    public CustomerLegacyController(LegacyProfileApplicationService legacyProfileService) {
        this.legacyProfileService = legacyProfileService;
    }

    @GetMapping("/{id}/legacy-profile")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<List<LegacyProfileItemResponse>> profile(@PathVariable long id,
                                                                HttpServletRequest request) {
        var items = legacyProfileService.profile(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id);
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.ok(items, requestId instanceof String value ? value : null);
    }
}
