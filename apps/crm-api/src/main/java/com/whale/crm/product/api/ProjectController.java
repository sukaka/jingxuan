package com.whale.crm.product.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.product.api.dto.CreateProjectRequest;
import com.whale.crm.product.api.dto.UpdateProjectRequest;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.Project;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final CatalogApplicationService service;

    public ProjectController(CatalogApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('product:read')")
    public ApiResponse<PageResponse<Project>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(required = false) String status, HttpServletRequest request) {
        return ApiResponse.ok(service.projects(TenantContext.currentTenantId(), keyword, status, page, size), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:write')")
    public ResponseEntity<ApiResponse<Project>> create(@Valid @RequestBody CreateProjectRequest body,
                                                       HttpServletRequest request) {
        Project created = service.createProject(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('product:write')")
    public ApiResponse<Project> update(@PathVariable long id, @Valid @RequestBody UpdateProjectRequest body,
                                       HttpServletRequest request) {
        return ApiResponse.ok(service.updateProject(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
