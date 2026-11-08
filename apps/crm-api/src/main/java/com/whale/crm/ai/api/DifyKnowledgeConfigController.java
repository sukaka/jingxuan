package com.whale.crm.ai.api;

import com.whale.crm.ai.api.dto.DifyKnowledgeConfigRequest;
import com.whale.crm.ai.api.dto.DifyKnowledgeConfigResponse;
import com.whale.crm.ai.application.DifyKnowledgeConfigService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/ai/knowledge/config")
@PreAuthorize("hasAuthority('ai:knowledge:config')")
public class DifyKnowledgeConfigController {
    private final DifyKnowledgeConfigService service;

    public DifyKnowledgeConfigController(DifyKnowledgeConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<DifyKnowledgeConfigResponse> get(HttpServletRequest request) {
        return ApiResponse.ok(service.get(SecuritySupport.requireCurrentUser().tenantId()), requestId(request));
    }

    @PutMapping
    public ApiResponse<DifyKnowledgeConfigResponse> save(@Valid @RequestBody DifyKnowledgeConfigRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.save(SecuritySupport.requireCurrentUser(), body, key), requestId(request));
    }

    @PostMapping("/test")
    public ApiResponse<DifyKnowledgeConfigResponse> test(
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        String requestId = requestId(request);
        return ApiResponse.ok(service.testConnection(SecuritySupport.requireCurrentUser(), key, requestId), requestId);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
