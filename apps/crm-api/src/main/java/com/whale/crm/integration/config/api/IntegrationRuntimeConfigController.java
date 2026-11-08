package com.whale.crm.integration.config.api;

import com.whale.crm.integration.config.api.dto.IntegrationRuntimeConfigRequest;
import com.whale.crm.integration.config.api.dto.IntegrationRuntimeConfigResponse;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/integrations/runtime-config")
@PreAuthorize("hasAuthority('integration:runtime:manage')")
public class IntegrationRuntimeConfigController {
    private final IntegrationRuntimeConfigService service;

    public IntegrationRuntimeConfigController(IntegrationRuntimeConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<IntegrationRuntimeConfigResponse> get(HttpServletRequest request) {
        return ApiResponse.ok(service.get(SecuritySupport.requireCurrentUser().tenantId()), requestId(request));
    }

    @PutMapping
    public ApiResponse<IntegrationRuntimeConfigResponse> save(
            @Valid @RequestBody IntegrationRuntimeConfigRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.save(SecuritySupport.requireCurrentUser(), body, key), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
