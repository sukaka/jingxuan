package com.whale.crm.risk.api;

import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.risk.api.dto.ClaimRiskCaseRequest;
import com.whale.crm.risk.api.dto.CloseRiskCaseRequest;
import com.whale.crm.risk.api.dto.ResolveRiskCaseRequest;
import com.whale.crm.risk.api.dto.RiskCaseDetailResponse;
import com.whale.crm.risk.api.dto.RiskCaseResponse;
import com.whale.crm.risk.application.RiskCaseApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/risks")
public class RiskCaseController {
    private final RiskCaseApplicationService service;

    public RiskCaseController(RiskCaseApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('risk:read')")
    public ApiResponse<PageResponse<RiskCaseResponse>> search(
            @RequestParam(required = false) @Pattern(regexp = "OPEN|ASSIGNED|RESOLVED|CLOSED") String status,
            @RequestParam(required = false) @Pattern(regexp = "HIGH|MEDIUM|LOW") String severity,
            @RequestParam(required = false) @Size(max = 64) String riskType,
            @RequestParam(required = false) @Size(max = 48) String sourceType,
            @RequestParam(required = false) @Size(max = 128) String sourceRef,
            @RequestParam(required = false) @Positive Long ownerUserId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(service.search(SecuritySupport.requireCurrentUser(), status, severity, riskType,
                sourceType, sourceRef, ownerUserId, keyword, page, size), requestId(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('risk:read')")
    public ApiResponse<RiskCaseDetailResponse> get(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.get(SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasAuthority('risk:write')")
    public ApiResponse<RiskCaseDetailResponse> claim(@PathVariable long id,
                                               @Valid @RequestBody ClaimRiskCaseRequest body,
                                               HttpServletRequest request) {
        return ApiResponse.ok(service.claim(SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('risk:write')")
    public ApiResponse<RiskCaseDetailResponse> resolve(@PathVariable long id,
                                                 @Valid @RequestBody ResolveRiskCaseRequest body,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.resolve(SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('risk:write')")
    public ApiResponse<RiskCaseDetailResponse> close(@PathVariable long id,
                                               @Valid @RequestBody CloseRiskCaseRequest body,
                                               HttpServletRequest request) {
        return ApiResponse.ok(service.close(SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
