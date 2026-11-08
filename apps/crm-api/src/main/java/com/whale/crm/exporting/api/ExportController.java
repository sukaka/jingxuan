package com.whale.crm.exporting.api;

import com.whale.crm.exporting.api.dto.CreateExportRequest;
import com.whale.crm.exporting.api.dto.ExportAccessLogResponse;
import com.whale.crm.exporting.api.dto.ExportDownloadResponse;
import com.whale.crm.exporting.api.dto.ExportJobResponse;
import com.whale.crm.exporting.application.ExportApplicationService;
import com.whale.crm.exporting.domain.ExportJob;
import com.whale.crm.exporting.domain.ExportStatus;
import com.whale.crm.exporting.domain.ExportType;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/api/v1/exports")
public class ExportController {
    private final ExportApplicationService service;

    public ExportController(ExportApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ExportJobResponse>> create(
            @Valid @RequestBody CreateExportRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        ExportJob job = service.create(SecuritySupport.requireCurrentUser(), body, key);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.response(job), requestId(request)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('export:read')")
    public ApiResponse<PageResponse<ExportJobResponse>> search(
            @RequestParam(required = false) ExportType exportType,
            @RequestParam(required = false) ExportStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        PageResponse<ExportJob> result = service.search(SecuritySupport.requireCurrentUser(), exportType, status, page, size);
        return ApiResponse.ok(new PageResponse<>(result.items().stream().map(service::response).toList(),
                result.total(), result.page(), result.size()), requestId(request));
    }

    @GetMapping("/access-logs")
    @PreAuthorize("hasAuthority('export:read')")
    public ApiResponse<PageResponse<ExportAccessLogResponse>> accessLogs(
            @RequestParam(required = false) Long jobId,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) @Pattern(regexp = "REQUEST|DOWNLOAD") String action,
            @RequestParam(required = false) @Pattern(regexp = "ALLOWED|DENIED") String result,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(service.accessLogs(SecuritySupport.requireCurrentUser(), jobId, actorId,
                action, result, page, size), requestId(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('export:read')")
    public ApiResponse<ExportJobResponse> get(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.response(service.get(SecuritySupport.requireCurrentUser(), id)), requestId(request));
    }

    @GetMapping("/{id}/download")
    public ApiResponse<ExportDownloadResponse> download(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.download(SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
