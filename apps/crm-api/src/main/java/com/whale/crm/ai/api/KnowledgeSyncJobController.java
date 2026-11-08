package com.whale.crm.ai.api;

import com.whale.crm.ai.api.dto.KnowledgeSyncJobResponse;
import com.whale.crm.ai.application.KnowledgeManagementService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/ai/knowledge/sync-jobs")
public class KnowledgeSyncJobController {
    private final KnowledgeManagementService managementService;

    public KnowledgeSyncJobController(KnowledgeManagementService managementService) {
        this.managementService = managementService;
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('ai:knowledge:publish')")
    public ApiResponse<KnowledgeSyncJobResponse> retry(@PathVariable long id,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.retry(SecuritySupport.requireCurrentUser(), id, key), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
