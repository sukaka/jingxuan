package com.whale.crm.reporting.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.reporting.api.dto.CampaignReportResponse;
import com.whale.crm.reporting.api.dto.OperationsDashboardResponse;
import com.whale.crm.reporting.api.dto.OperationsReportResponse;
import com.whale.crm.reporting.api.dto.PerformanceReportResponse;
import com.whale.crm.reporting.api.dto.ReportCriteriaRequest;
import com.whale.crm.reporting.api.dto.SkuQualityReportResponse;
import com.whale.crm.reporting.application.ReportingApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/reports")
public class ReportingController {
    private final ReportingApplicationService service;

    public ReportingController(ReportingApplicationService service) {
        this.service = service;
    }

    @GetMapping("/operations-dashboard")
    @PreAuthorize("hasAuthority('report:read')")
    public ApiResponse<OperationsDashboardResponse> operationsDashboard(
            @Valid @ModelAttribute ReportCriteriaRequest criteria, HttpServletRequest request) {
        return ApiResponse.ok(service.operationsDashboard(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), criteria.toCriteria()), requestId(request));
    }

    @GetMapping("/operations")
    @PreAuthorize("hasAuthority('report:read')")
    public ApiResponse<OperationsReportResponse> operations(
            @Valid @ModelAttribute ReportCriteriaRequest criteria,
            @RequestParam(defaultValue = "NONE") @Size(max = 32) String drillDownType,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int drillDownLimit,
            HttpServletRequest request) {
        return ApiResponse.ok(service.operations(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                criteria.toCriteria(), drillDownType, drillDownLimit), requestId(request));
    }

    @GetMapping("/performance")
    @PreAuthorize("hasAuthority('report:read')")
    public ApiResponse<PerformanceReportResponse> performance(
            @Valid @ModelAttribute ReportCriteriaRequest criteria,
            @RequestParam(defaultValue = "MANAGER") @Size(max = 32) String performanceRole,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "NONE") @Size(max = 32) String drillDownType,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int drillDownLimit,
            HttpServletRequest request) {
        return ApiResponse.ok(service.performance(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                criteria.toCriteria(), performanceRole, page, size, drillDownType, drillDownLimit), requestId(request));
    }

    @GetMapping("/campaigns")
    @PreAuthorize("hasAuthority('report:read')")
    public ApiResponse<CampaignReportResponse> campaigns(
            @Valid @ModelAttribute ReportCriteriaRequest criteria,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "NONE") @Size(max = 32) String drillDownType,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int drillDownLimit,
            HttpServletRequest request) {
        return ApiResponse.ok(service.campaigns(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                criteria.toCriteria(), page, size, drillDownType, drillDownLimit), requestId(request));
    }

    @GetMapping("/sku-quality")
    @PreAuthorize("hasAuthority('report:read')")
    public ApiResponse<SkuQualityReportResponse> skuQuality(
            @Valid @ModelAttribute ReportCriteriaRequest criteria,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "3") @Min(0) @Max(1000) int lowFrequencyThreshold,
            @RequestParam(defaultValue = "NONE") @Size(max = 32) String drillDownType,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int drillDownLimit,
            HttpServletRequest request) {
        return ApiResponse.ok(service.skuQuality(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                criteria.toCriteria(), page, size, lowFrequencyThreshold, drillDownType, drillDownLimit), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
