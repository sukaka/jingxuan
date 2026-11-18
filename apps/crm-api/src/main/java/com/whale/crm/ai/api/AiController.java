package com.whale.crm.ai.api;

import com.whale.crm.ai.api.dto.AiJobResponse;
import com.whale.crm.ai.api.dto.AiQualitySummaryResponse;
import com.whale.crm.ai.api.dto.AiReplyDecisionRequest;
import com.whale.crm.ai.api.dto.AiReplySuggestionResponse;
import com.whale.crm.ai.api.dto.ConfirmAiJobRequest;
import com.whale.crm.ai.api.dto.CreateAiJobRequest;
import com.whale.crm.ai.api.dto.GenerateAiReplyRequest;
import com.whale.crm.ai.application.AiJobApplicationService;
import com.whale.crm.ai.application.AiReplySuggestionService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
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
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiJobApplicationService service;
    private final AiReplySuggestionService replyService;

    public AiController(AiJobApplicationService service, AiReplySuggestionService replyService) {
        this.service = service;
        this.replyService = replyService;
    }

    @PostMapping("/jobs")
    @PreAuthorize("hasAuthority('ai:write')")
    public ResponseEntity<ApiResponse<AiJobResponse>> create(@Valid @RequestBody CreateAiJobRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(SecuritySupport.requireCurrentUser(), body, key), requestId(request)));
    }

    @GetMapping("/jobs/{id}")
    @PreAuthorize("hasAuthority('ai:read')")
    public ApiResponse<AiJobResponse> get(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.get(SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @GetMapping("/jobs")
    @PreAuthorize("hasAuthority('ai:read')")
    public ApiResponse<PageResponse<AiJobResponse>> search(@RequestParam(required = false) String conversationId,
            @RequestParam(required = false) @Pattern(regexp = "QUEUED|RUNNING|RETRY|PROPOSED|FAILED|CONFIRMED|REJECTED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, HttpServletRequest request) {
        return ApiResponse.ok(service.search(SecuritySupport.requireCurrentUser(), conversationId, status, page, size), requestId(request));
    }

    @PostMapping("/jobs/{id}/confirmation")
    @PreAuthorize("hasAuthority('ai:confirm') and hasAuthority('task:write')")
    public ApiResponse<AiJobResponse> confirm(@PathVariable long id, @Valid @RequestBody ConfirmAiJobRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key, HttpServletRequest request) {
        return ApiResponse.ok(service.confirm(SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }

    @GetMapping("/quality/summary")
    @PreAuthorize("hasAuthority('wecom:monitor') and hasAuthority('ai:read')")
    public ApiResponse<AiQualitySummaryResponse> quality(HttpServletRequest request) {
        return ApiResponse.ok(service.quality(SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @PostMapping("/replies/generate")
    @PreAuthorize("hasAuthority('ai:write') and hasAuthority('quickreply:view')")
    public ResponseEntity<ApiResponse<AiReplySuggestionResponse>> generateReply(
            @Valid @RequestBody GenerateAiReplyRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                replyService.generate(SecuritySupport.requireCurrentUser(), body, key), requestId(request)));
    }

    @GetMapping("/replies/{id}")
    @PreAuthorize("hasAuthority('ai:read') and hasAuthority('quickreply:view')")
    public ApiResponse<AiReplySuggestionResponse> getReply(@PathVariable @Positive long id, HttpServletRequest request) {
        return ApiResponse.ok(replyService.get(SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @GetMapping("/replies")
    @PreAuthorize("hasAuthority('ai:read') and hasAuthority('quickreply:view')")
    public ApiResponse<List<AiReplySuggestionResponse>> replyHistory(@RequestParam @Positive long customerId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size, HttpServletRequest request) {
        return ApiResponse.ok(replyService.history(SecuritySupport.requireCurrentUser(), customerId, size), requestId(request));
    }

    @PostMapping("/replies/{id}/decision")
    @PreAuthorize("hasAuthority('ai:write') and hasAuthority('quickreply:view')")
    public ApiResponse<AiReplySuggestionResponse> decideReply(@PathVariable @Positive long id,
            @Valid @RequestBody AiReplyDecisionRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(replyService.decide(SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
