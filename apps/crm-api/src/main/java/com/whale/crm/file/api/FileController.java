package com.whale.crm.file.api;

import com.whale.crm.file.api.dto.DownloadResponse;
import com.whale.crm.file.api.dto.FileMetadataResponse;
import com.whale.crm.file.api.dto.UploadCompleteRequest;
import com.whale.crm.file.api.dto.UploadInitRequest;
import com.whale.crm.file.api.dto.UploadInitResponse;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileApplicationService fileService;

    public FileController(FileApplicationService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload-init")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UploadInitResponse>> uploadInit(@Valid @RequestBody UploadInitRequest body,
                                                                      HttpServletRequest request) {
        UploadInitResponse created = fileService.uploadInit(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, requestId(request)));
    }

    @PostMapping("/upload-complete")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<FileMetadataResponse> uploadComplete(@Valid @RequestBody UploadCompleteRequest body,
                                                            HttpServletRequest request) {
        return ApiResponse.ok(fileService.uploadComplete(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body), requestId(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<FileMetadataResponse> metadata(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(fileService.metadataGeneric(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<DownloadResponse> download(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(fileService.downloadGeneric(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
