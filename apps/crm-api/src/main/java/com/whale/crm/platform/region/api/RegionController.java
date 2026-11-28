package com.whale.crm.platform.region.api;

import com.whale.crm.platform.region.api.dto.RegionNodeResponse;
import com.whale.crm.platform.region.application.RegionCatalogService;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 全国标准行政区划树只读查询（dictionary:read，所有登录角色具备）。
 */
@RestController
@RequestMapping("/api/v1/regions")
public class RegionController {

    private final RegionCatalogService regionCatalogService;

    public RegionController(RegionCatalogService regionCatalogService) {
        this.regionCatalogService = regionCatalogService;
    }

    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('dictionary:read')")
    public ResponseEntity<ApiResponse<List<RegionNodeResponse>>> tree(HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePrivate())
                .body(ApiResponse.ok(regionCatalogService.tree(), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
