package com.whale.crm.settlement.api;

import com.whale.crm.institution.api.InstitutionBaseController;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.settlement.api.dto.CreateSettlementBatchRequest;
import com.whale.crm.settlement.api.dto.RecordSettlementPaymentRequest;
import com.whale.crm.settlement.api.dto.SettlementBatchDetail;
import com.whale.crm.settlement.api.dto.VerifySettlementBatchRequest;
import com.whale.crm.settlement.application.SettlementApplicationService;
import com.whale.crm.settlement.domain.SettlementBatch;
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
@RequestMapping("/api/v1/settlements")
public class SettlementController extends InstitutionBaseController {
    private final SettlementApplicationService service;

    public SettlementController(SettlementApplicationService service) {
        this.service = service;
    }

    @PostMapping("/batches")
    @PreAuthorize("hasAuthority('settlement:write')")
    public ResponseEntity<ApiResponse<SettlementBatchDetail>> create(@Valid @RequestBody CreateSettlementBatchRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key, HttpServletRequest request) {
        SettlementBatchDetail created = service.create(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping("/batches")
    @PreAuthorize("hasAuthority('settlement:read')")
    public ApiResponse<PageResponse<SettlementBatch>> search(
            @RequestParam(required = false) Long institutionId,
            @RequestParam(required = false) @Pattern(regexp = "MONTHLY|PER_REDEMPTION") String settlementType,
            @RequestParam(required = false) @Size(max = 32) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(service.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                institutionId, settlementType, status, page, size), requestId(request));
    }

    @GetMapping("/batches/{id}")
    @PreAuthorize("hasAuthority('settlement:read')")
    public ApiResponse<SettlementBatchDetail> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.detail(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id),
                requestId(request));
    }

    @PostMapping("/batches/{id}/verification")
    @PreAuthorize("hasAuthority('settlement:write')")
    public ApiResponse<SettlementBatchDetail> verify(@PathVariable long id,
            @Valid @RequestBody VerifySettlementBatchRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key, HttpServletRequest request) {
        return ApiResponse.ok(service.verify(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, key), requestId(request));
    }

    @PostMapping("/batches/{id}/payments")
    @PreAuthorize("hasAuthority('settlement:write')")
    public ApiResponse<SettlementBatchDetail> pay(@PathVariable long id,
            @Valid @RequestBody RecordSettlementPaymentRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key, HttpServletRequest request) {
        return ApiResponse.ok(service.pay(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, key), requestId(request));
    }
}
