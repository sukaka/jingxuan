package com.whale.crm.reporting.api;

import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.reporting.api.dto.WecomAnalyticsResponse;
import com.whale.crm.reporting.application.WecomAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Validated
@RestController
@RequestMapping("/api/v1/reports/wecom-analytics")
public class WecomAnalyticsController {
    private final WecomAnalyticsService service;

    public WecomAnalyticsController(WecomAnalyticsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('report:read')")
    public ApiResponse<WecomAnalyticsResponse> analytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Min(1) Long employeeId, HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.ok(service.analytics(SecuritySupport.requireCurrentUser(), from, to, employeeId),
                value instanceof String id ? id : null);
    }
}
