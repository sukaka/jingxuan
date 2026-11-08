package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.OwnerOptionResponse;
import com.whale.crm.customer.application.StewardDirectory;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/customer-owner-options")
public class CustomerOwnerOptionController {

    private final StewardDirectory stewardDirectory;

    public CustomerOwnerOptionController(StewardDirectory stewardDirectory) {
        this.stewardDirectory = stewardDirectory;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<List<OwnerOptionResponse>> options(HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        List<OwnerOptionResponse> items = stewardDirectory.optionsFor(TenantContext.currentTenantId(), user)
                .stream()
                .map(row -> new OwnerOptionResponse(row.id(), row.displayName(), row.teamId(), row.teamName()))
                .toList();
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.ok(items, requestId instanceof String id ? id : null);
    }
}
