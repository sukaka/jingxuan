package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.CustomerTimelinePageResponse;
import com.whale.crm.customer.application.CustomerTimelineApplicationService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerTimelineController {
    private final CustomerTimelineApplicationService timelineService;

    public CustomerTimelineController(CustomerTimelineApplicationService timelineService) {
        this.timelineService = timelineService;
    }

    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<CustomerTimelinePageResponse> timeline(
            @PathVariable long id,
            @RequestParam(required = false) @Size(max = 1024) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        CustomerTimelinePageResponse result = timelineService.timeline(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, cursor, size);
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.ok(result, value instanceof String text ? text : null);
    }
}
