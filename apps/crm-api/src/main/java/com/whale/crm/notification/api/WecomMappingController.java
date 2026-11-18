package com.whale.crm.notification.api;

import com.whale.crm.notification.api.dto.CreateWecomMappingRequest;
import com.whale.crm.notification.api.dto.UpdateWecomMappingRequest;
import com.whale.crm.notification.api.dto.WecomMappingResponse;
import com.whale.crm.notification.application.WecomMappingApplicationService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/notification-settings/wecom-mappings")
public class WecomMappingController {
    private final WecomMappingApplicationService service;

    public WecomMappingController(WecomMappingApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('notification:delivery:read')")
    public ApiResponse<List<WecomMappingResponse>> list(HttpServletRequest request) {
        return ApiResponse.ok(service.list(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser()),
                requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('notification:delivery:retry')")
    public ApiResponse<WecomMappingResponse> create(@Valid @RequestBody CreateWecomMappingRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128)
            @Pattern(regexp = ".*\\S.*") String idempotencyKey, HttpServletRequest request) {
        return ApiResponse.ok(service.create(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                body, idempotencyKey), requestId(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('notification:delivery:retry')")
    public ApiResponse<WecomMappingResponse> update(@PathVariable long id,
            @Valid @RequestBody UpdateWecomMappingRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128)
            @Pattern(regexp = ".*\\S.*") String idempotencyKey, HttpServletRequest request) {
        return ApiResponse.ok(service.update(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, idempotencyKey), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
