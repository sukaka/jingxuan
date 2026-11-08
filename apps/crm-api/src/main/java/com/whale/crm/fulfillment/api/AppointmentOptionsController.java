package com.whale.crm.fulfillment.api;

import com.whale.crm.fulfillment.api.dto.AppointmentOptionsResponse;
import com.whale.crm.fulfillment.application.AppointmentApplicationService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/appointment-options")
public class AppointmentOptionsController extends BaseFulfillmentController {
    private final AppointmentApplicationService appointmentService;

    public AppointmentOptionsController(AppointmentApplicationService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('appointment:read') and (hasAuthority('customer:read') or hasAuthority('redemption:post'))")
    public ApiResponse<AppointmentOptionsResponse> options(@RequestParam(required = false) Long customerId,
                                                           @RequestParam(required = false) Long appointmentId,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(appointmentService.options(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), customerId, appointmentId), requestId(request));
    }
}
