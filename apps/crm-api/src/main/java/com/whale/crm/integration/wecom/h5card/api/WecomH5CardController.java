package com.whale.crm.integration.wecom.h5card.api;

import com.whale.crm.integration.wecom.h5card.api.dto.CreateWecomH5CardRequest;
import com.whale.crm.integration.wecom.h5card.api.dto.DisableWecomH5CardRequest;
import com.whale.crm.integration.wecom.h5card.api.dto.PublicWecomH5CardResponse;
import com.whale.crm.integration.wecom.h5card.api.dto.WecomH5CardResponse;
import com.whale.crm.integration.wecom.h5card.api.dto.WecomH5CardUsageRequest;
import com.whale.crm.integration.wecom.h5card.api.dto.WecomH5CardUsageResponse;
import com.whale.crm.integration.wecom.h5card.application.WecomH5CardService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
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

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1")
public class WecomH5CardController {
    private final WecomH5CardService service;

    public WecomH5CardController(WecomH5CardService service) {
        this.service = service;
    }

    @PostMapping("/h5/cards")
    @PreAuthorize("hasAuthority('wecom:sidebar:view') and hasAuthority('product:read')")
    public ResponseEntity<ApiResponse<WecomH5CardResponse>> create(@Valid @RequestBody CreateWecomH5CardRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                service.create(SecuritySupport.requireCurrentUser(), body, key), requestId(request)));
    }

    @GetMapping("/h5/cards")
    @PreAuthorize("hasAuthority('wecom:sidebar:view')")
    public ApiResponse<List<WecomH5CardResponse>> history(@RequestParam @Positive long customerId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size, HttpServletRequest request) {
        return ApiResponse.ok(service.history(SecuritySupport.requireCurrentUser(), customerId, size), requestId(request));
    }

    @PostMapping("/h5/cards/{id}/disable")
    @PreAuthorize("hasAuthority('wecom:sidebar:view')")
    public ApiResponse<WecomH5CardResponse> disable(@PathVariable @Positive long id,
            @Valid @RequestBody DisableWecomH5CardRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key, HttpServletRequest request) {
        return ApiResponse.ok(service.disable(SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }

    @PostMapping("/h5/cards/{id}/usage")
    @PreAuthorize("hasAuthority('wecom:sidebar:view')")
    public ResponseEntity<ApiResponse<WecomH5CardUsageResponse>> recordUsage(@PathVariable @Positive long id,
            @Valid @RequestBody WecomH5CardUsageRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                service.recordUsage(SecuritySupport.requireCurrentUser(), id, body, key), requestId(request)));
    }

    @GetMapping("/public/h5-cards/{shareKey}")
    public ApiResponse<PublicWecomH5CardResponse> publicView(
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String shareKey, HttpServletRequest request) {
        return ApiResponse.ok(service.publicView(shareKey), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
