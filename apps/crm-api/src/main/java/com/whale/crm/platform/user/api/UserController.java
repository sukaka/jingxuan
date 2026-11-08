package com.whale.crm.platform.user.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.user.api.dto.AssignRolesRequest;
import com.whale.crm.platform.user.api.dto.CreateUserRequest;
import com.whale.crm.platform.user.api.dto.TeamOptionResponse;
import com.whale.crm.platform.user.api.dto.UpdateUserRequest;
import com.whale.crm.platform.user.api.dto.UserSummaryResponse;
import com.whale.crm.platform.user.application.UserApplicationService;
import com.whale.crm.platform.user.domain.UserSearchFilter;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理（user:read/user:write）。tenantId 与操作人一律从登录主体解析，不信任客户端传入。
 */
@Validated
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserApplicationService userService;

    public UserController(UserApplicationService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<PageResponse<UserSummaryResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|DISABLED", message = "status 仅支持 ACTIVE/DISABLED")
            String status,
            @RequestParam(required = false) @Pattern(regexp = "EMPLOYEE|INSTITUTION", message = "userType 仅支持 EMPLOYEE/INSTITUTION")
            String userType,
            @RequestParam(required = false) @Min(1) Long teamId,
            @RequestParam(required = false) @Size(max = 64) String roleCode,
            HttpServletRequest request) {
        var filter = new UserSearchFilter(keyword, status, userType, teamId, roleCode);
        return ApiResponse.ok(
                userService.search(TenantContext.currentTenantId(), filter, page, size),
                requestId(request));
    }

    @GetMapping("/teams")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<java.util.List<TeamOptionResponse>> teams(HttpServletRequest request) {
        return ApiResponse.ok(userService.listTeams(TenantContext.currentTenantId()).stream()
                .map(row -> new TeamOptionResponse(row.id(), row.teamName()))
                .toList(), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:write')")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> create(@Valid @RequestBody CreateUserRequest body,
                                                                   HttpServletRequest request) {
        UserSummaryResponse created = userService.create(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('user:write')")
    public ApiResponse<UserSummaryResponse> update(@PathVariable long id,
                                                   @Valid @RequestBody UpdateUserRequest body,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(
                userService.update(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(),
                        id, body),
                requestId(request));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:write')")
    public ApiResponse<UserSummaryResponse> assignRoles(@PathVariable long id,
                                                        @Valid @RequestBody AssignRolesRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(
                userService.assignRoles(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(),
                        id, body),
                requestId(request));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('user:write')")
    public ApiResponse<UserSummaryResponse> disable(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(
                userService.disable(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id),
                requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
