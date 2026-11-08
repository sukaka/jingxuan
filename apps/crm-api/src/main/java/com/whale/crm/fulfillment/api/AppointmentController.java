package com.whale.crm.fulfillment.api;

import com.whale.crm.fulfillment.api.dto.AppointmentDetail;
import com.whale.crm.fulfillment.api.dto.AppointmentTransitionRequest;
import com.whale.crm.fulfillment.api.dto.CreateAppointmentRequest;
import com.whale.crm.fulfillment.api.dto.PostRedemptionRequest;
import com.whale.crm.fulfillment.api.dto.RedemptionDetail;
import com.whale.crm.fulfillment.application.AppointmentApplicationService;
import com.whale.crm.fulfillment.application.RedemptionApplicationService;
import com.whale.crm.fulfillment.domain.Appointment;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/v1/appointments")
public class AppointmentController extends BaseFulfillmentController {
    private final AppointmentApplicationService appointmentService;
    private final RedemptionApplicationService redemptionService;

    public AppointmentController(AppointmentApplicationService appointmentService,
                                 RedemptionApplicationService redemptionService) {
        this.appointmentService = appointmentService;
        this.redemptionService = redemptionService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('appointment:read')")
    public ApiResponse<PageResponse<Appointment>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long institutionId,
            @RequestParam(required = false) @Size(max = 48) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(appointmentService.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                customerId, institutionId, status, page, size), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('appointment:write')")
    public ResponseEntity<ApiResponse<AppointmentDetail>> create(
            @Valid @RequestBody CreateAppointmentRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        AppointmentDetail created = appointmentService.create(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('appointment:read')")
    public ApiResponse<AppointmentDetail> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(appointmentService.detail(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('appointment:confirm')")
    public ApiResponse<AppointmentDetail> confirm(@PathVariable long id, @Valid @RequestBody AppointmentTransitionRequest body,
                                                  @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(appointmentService.transition(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, "confirm", body, key), requestId(request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('appointment:confirm')")
    public ApiResponse<AppointmentDetail> reject(@PathVariable long id, @Valid @RequestBody AppointmentTransitionRequest body,
                                                 @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(appointmentService.transition(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, "reject", body, key), requestId(request));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('appointment:write')")
    public ApiResponse<AppointmentDetail> cancel(@PathVariable long id, @Valid @RequestBody AppointmentTransitionRequest body,
                                                 @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(appointmentService.transition(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, "cancel", body, key), requestId(request));
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize("hasAuthority('appointment:confirm')")
    public ApiResponse<AppointmentDetail> checkIn(@PathVariable long id, @Valid @RequestBody AppointmentTransitionRequest body,
                                                  @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(appointmentService.transition(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, "check-in", body, key), requestId(request));
    }

    @PostMapping("/{id}/service-done")
    @PreAuthorize("hasAuthority('appointment:confirm')")
    public ApiResponse<AppointmentDetail> serviceDone(@PathVariable long id, @Valid @RequestBody AppointmentTransitionRequest body,
                                                      @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(appointmentService.transition(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, "service-done", body, key), requestId(request));
    }

    @PostMapping("/{id}/no-show")
    @PreAuthorize("hasAuthority('appointment:confirm')")
    public ApiResponse<AppointmentDetail> noShow(@PathVariable long id, @Valid @RequestBody AppointmentTransitionRequest body,
                                                 @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(appointmentService.transition(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, "no-show", body, key), requestId(request));
    }

    @PostMapping("/{id}/redemptions")
    @PreAuthorize("hasAuthority('redemption:post')")
    public ApiResponse<RedemptionDetail> postRedemption(@PathVariable long id, @Valid @RequestBody PostRedemptionRequest body,
                                                        @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(redemptionService.post(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, key), requestId(request));
    }
}
