package com.whale.crm.integration.wecom.api;

import com.whale.crm.integration.wecom.api.dto.WecomContactSyncRequest;
import com.whale.crm.integration.wecom.application.WecomContactSyncService;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRun;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/integrations/wecom/contact-sync")
public class WecomContactSyncController extends WecomBaseController {
    private final WecomContactSyncService service;

    public WecomContactSyncController(WecomContactSyncService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasAuthority('wecom:monitor')")
    public ApiResponse<WecomContactSyncRun> sync(@Valid @RequestBody(required = false) WecomContactSyncRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(service.sync(user, body == null ? null : body.type()), requestId(request));
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('wecom:read')")
    public ApiResponse<List<WecomContactSyncRun>> runs(HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(service.runs(user.tenantId()), requestId(request));
    }
}
