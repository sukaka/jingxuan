package com.whale.crm.annualreport.api;

import com.whale.crm.annualreport.api.dto.AnnualReportBatchGenerateRequest;
import com.whale.crm.annualreport.api.dto.AnnualReportBatchGenerateResponse;
import com.whale.crm.annualreport.api.dto.AnnualReportDisableRequest;
import com.whale.crm.annualreport.api.dto.AnnualReportItemResponse;
import com.whale.crm.annualreport.api.dto.AnnualReportSessionResponse;
import com.whale.crm.annualreport.api.dto.AnnualReportSummaryResponse;
import com.whale.crm.annualreport.api.dto.CreateAnnualReportRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualBookingRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualBookingResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualReportResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualTelemetryRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualTelemetryResponse;
import com.whale.crm.annualreport.application.AnnualReportService;
import com.whale.crm.annualreport.application.AnnualReportTelemetryService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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

import java.time.Year;
import java.util.List;

/**
 * 客户年终购买轨迹 H5 管理、统计及 C 端公开接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class AnnualReportController {

    private final AnnualReportService service;
    private final AnnualReportTelemetryService telemetryService;

    public AnnualReportController(AnnualReportService service,
                                  AnnualReportTelemetryService telemetryService) {
        this.service = service;
        this.telemetryService = telemetryService;
    }

    @PostMapping("/annual-reports")
    @PreAuthorize("hasAuthority('customer:write')")
    public ResponseEntity<ApiResponse<AnnualReportItemResponse>> create(
            @Valid @RequestBody CreateAnnualReportRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                service.create(SecuritySupport.requireCurrentUser(), body, idempotencyKey), requestId(request)));
    }

    @PostMapping("/annual-reports/batch-generate")
    @PreAuthorize("hasAuthority('customer:write')")
    public ResponseEntity<ApiResponse<AnnualReportBatchGenerateResponse>> batchGenerate(
            @Valid @RequestBody AnnualReportBatchGenerateRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                service.batchGenerate(SecuritySupport.requireCurrentUser(), body, idempotencyKey), requestId(request)));
    }

    @GetMapping("/annual-reports")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<PageResponse<AnnualReportItemResponse>> search(
            @RequestParam(required = false) @Min(2020) @Max(2099) Integer reportYear,
            @RequestParam(required = false) @Positive Long customerId,
            @RequestParam(required = false) @Pattern(regexp = "^(ACTIVE|DISABLED)$") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(
                service.search(SecuritySupport.requireCurrentUser(), reportYear, customerId, status, page, size),
                requestId(request));
    }

    @GetMapping("/annual-reports/summary")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<AnnualReportSummaryResponse> summary(
            @RequestParam(required = false) @Min(2020) @Max(2099) Integer reportYear,
            HttpServletRequest request) {
        int year = reportYear != null ? reportYear : Year.now().getValue();
        return ApiResponse.ok(service.summary(SecuritySupport.requireCurrentUser(), year), requestId(request));
    }

    @GetMapping("/annual-reports/{id}/sessions")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<List<AnnualReportSessionResponse>> sessions(
            @PathVariable @Positive long id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            HttpServletRequest request) {
        return ApiResponse.ok(service.listSessions(SecuritySupport.requireCurrentUser(), id, limit), requestId(request));
    }

    @PostMapping("/annual-reports/{id}/disable")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<AnnualReportItemResponse> disable(
            @PathVariable @Positive long id,
            @Valid @RequestBody AnnualReportDisableRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(
                service.disable(SecuritySupport.requireCurrentUser(), id, body, idempotencyKey), requestId(request));
    }

    @GetMapping("/public/annual-reports/{shareKey}")
    public ApiResponse<PublicAnnualReportResponse> publicView(
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String shareKey,
            HttpServletRequest request) {
        return ApiResponse.ok(telemetryService.publicView(shareKey), requestId(request));
    }

    @PostMapping("/public/annual-reports/{shareKey}/telemetry")
    public ApiResponse<PublicAnnualTelemetryResponse> publicTelemetry(
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String shareKey,
            @Valid @RequestBody PublicAnnualTelemetryRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(telemetryService.recordTelemetry(shareKey, body), requestId(request));
    }

    @PostMapping("/public/annual-reports/{shareKey}/booking-intent")
    public ApiResponse<PublicAnnualBookingResponse> publicBookingIntent(
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String shareKey,
            @Valid @RequestBody PublicAnnualBookingRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(telemetryService.submitBookingIntent(shareKey, body), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
