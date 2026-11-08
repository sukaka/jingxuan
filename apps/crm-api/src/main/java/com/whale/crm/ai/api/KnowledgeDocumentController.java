package com.whale.crm.ai.api;

import com.whale.crm.ai.api.dto.KnowledgeDocumentResponse;
import com.whale.crm.ai.api.dto.KnowledgeSyncJobResponse;
import com.whale.crm.ai.api.dto.PublishKnowledgeDocumentRequest;
import com.whale.crm.ai.application.KnowledgeManagementService;
import com.whale.crm.ai.application.KnowledgePublicationService;
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
@RequestMapping("/api/v1/ai/knowledge/documents")
public class KnowledgeDocumentController {
    private final KnowledgePublicationService publicationService;
    private final KnowledgeManagementService managementService;

    public KnowledgeDocumentController(KnowledgePublicationService publicationService,
                                       KnowledgeManagementService managementService) {
        this.publicationService = publicationService;
        this.managementService = managementService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ai:knowledge:read','ai:knowledge:publish')")
    public ApiResponse<PageResponse<KnowledgeDocumentResponse>> search(
            @RequestParam(required = false) @Pattern(regexp = "WAITING|SYNCING|ACTIVE|FAILED|DISABLED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.search(SecuritySupport.requireCurrentUser(), status, page, size),
                requestId(request));
    }

    @PostMapping("/publications")
    @PreAuthorize("hasAuthority('ai:knowledge:publish')")
    public ResponseEntity<ApiResponse<KnowledgeSyncJobResponse>> publish(
            @Valid @RequestBody PublishKnowledgeDocumentRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        KnowledgeSyncJobResponse created = publicationService.publish(SecuritySupport.requireCurrentUser(), body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('ai:knowledge:publish')")
    public ApiResponse<KnowledgeDocumentResponse> disable(@PathVariable long id,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.disable(SecuritySupport.requireCurrentUser(), id, key), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
