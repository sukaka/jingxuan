package com.whale.crm.platform.user.application;

import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.auth.domain.LoginSessionRepository;
import com.whale.crm.platform.user.api.dto.AssignRolesRequest;
import com.whale.crm.platform.user.api.dto.CreateUserRequest;
import com.whale.crm.platform.user.api.dto.UpdateUserRequest;
import com.whale.crm.platform.user.api.dto.UserSummaryResponse;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserListRow;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.user.domain.UserSearchFilter;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户管理应用服务。所有写操作落审计（脱敏快照，不含密码/哈希）；
 * 停用账号同时吊销其全部登录会话；角色/权限变更因每请求实时解析而立即生效。
 */
@Service
public class UserApplicationService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final LoginSessionRepository loginSessionRepository;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    public UserApplicationService(UserRepository userRepository,
                                  RoleRepository roleRepository,
                                  LoginSessionRepository loginSessionRepository,
                                  AuditService auditService,
                                  PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.loginSessionRepository = loginSessionRepository;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserSummaryResponse> search(long tenantId, UserSearchFilter filter, int page, int size) {
        int offset = (page - 1) * size;
        List<UserListRow> rows = userRepository.search(tenantId, filter, offset, size);
        Map<Long, List<String>> rolesByUser = new LinkedHashMap<>();
        roleRepository.findActiveRolePairs(tenantId, rows.stream().map(UserListRow::getId).toList())
                .forEach(pair -> rolesByUser
                        .computeIfAbsent(pair.getUserId(), key -> new java.util.ArrayList<>())
                        .add(pair.getRoleCode()));
        List<UserSummaryResponse> items = rows.stream()
                .map(row -> toSummary(row, rolesByUser.getOrDefault(row.getId(), List.of())))
                .toList();
        long total = userRepository.count(tenantId, filter);
        return new PageResponse<>(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public List<UserRepository.TeamOptionRow> listTeams(long tenantId) {
        return userRepository.listTeams(tenantId);
    }

    @Transactional
    public UserSummaryResponse create(long tenantId, long actorUserId, CreateUserRequest request) {
        if (userRepository.existsByUsername(tenantId, request.username())) {
            throw new BusinessException(ErrorCode.CONFLICT, "登录名已存在");
        }
        Long orgId = userRepository.findDefaultOrgId(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "租户下无可用组织"));
        validateTeam(tenantId, request.teamId());
        validateRoleIds(tenantId, request.roleIds());

        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        user.setOrgId(orgId);
        user.setTeamId(request.teamId());
        user.setUsername(request.username());
        user.setDisplayName(request.displayName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setUserType(request.userType());
        user.setStatus(SysUser.STATUS_ACTIVE);
        userRepository.insert(user);

        if (request.roleIds() != null && !request.roleIds().isEmpty()) {
            roleRepository.replaceUserRoles(tenantId, user.getId(), request.roleIds());
        }

        auditService.record(tenantId, actorUserId, AuditService.ACTION_USER_CREATE, "USER",
                Long.toString(user.getId()), null, userSnapshot(user, roleRepository.findActiveRoleCodes(tenantId, user.getId())),
                null);
        SysUser persisted = userRepository.findById(tenantId, user.getId()).orElseThrow();
        return toSummary(persisted, null, roleRepository.findActiveRoleCodes(tenantId, user.getId()));
    }

    @Transactional
    public UserSummaryResponse update(long tenantId, long actorUserId, long userId, UpdateUserRequest request) {
        SysUser existing = userRepository.findById(tenantId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在或不可见"));
        validateTeam(tenantId, request.teamId());

        String newPasswordHash = request.password() != null ? passwordEncoder.encode(request.password()) : null;
        int updated = userRepository.updateProfile(tenantId, userId, request.version(),
                request.displayName(), request.teamId(), newPasswordHash);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }

        if (newPasswordHash != null) {
            loginSessionRepository.revokeAllByUserId(tenantId, userId, Instant.now());
        }

        SysUser after = userRepository.findById(tenantId, userId).orElseThrow();
        List<String> roles = roleRepository.findActiveRoleCodes(tenantId, userId);
        Map<String, Object> beforeSnapshot = userSnapshot(existing, roles);
        Map<String, Object> afterSnapshot = userSnapshot(after, roles);
        if (newPasswordHash != null) {
            afterSnapshot.put("passwordReset", true);
        }
        auditService.record(tenantId, actorUserId, AuditService.ACTION_USER_UPDATE, "USER",
                Long.toString(userId), beforeSnapshot, afterSnapshot, null);
        return toSummary(after, null, roles);
    }

    @Transactional
    public UserSummaryResponse assignRoles(long tenantId, long actorUserId, long userId, AssignRolesRequest request) {
        SysUser existing = userRepository.findById(tenantId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在或不可见"));
        List<Long> roleIds = request.roleIds() == null ? List.of() : request.roleIds();
        validateRoleIds(tenantId, roleIds);

        List<String> beforeRoles = roleRepository.findActiveRoleCodes(tenantId, userId);
        roleRepository.replaceUserRoles(tenantId, userId, roleIds);
        List<String> afterRoles = roleRepository.findActiveRoleCodes(tenantId, userId);

        auditService.record(tenantId, actorUserId, AuditService.ACTION_USER_ROLE_ASSIGN, "USER",
                Long.toString(userId), Map.of("roles", beforeRoles), Map.of("roles", afterRoles), null);
        return toSummary(existing, null, afterRoles);
    }

    @Transactional
    public UserSummaryResponse disable(long tenantId, long actorUserId, long userId) {
        SysUser existing = userRepository.findById(tenantId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在或不可见"));
        if (userId == actorUserId) {
            throw new BusinessException(ErrorCode.CONFLICT, "不能停用当前登录账号");
        }
        if (!existing.isActive()) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户已处于停用状态");
        }
        userRepository.updateStatus(tenantId, userId, SysUser.STATUS_DISABLED);
        loginSessionRepository.revokeAllByUserId(tenantId, userId, Instant.now());

        auditService.record(tenantId, actorUserId, AuditService.ACTION_USER_DISABLE, "USER",
                Long.toString(userId), userSnapshot(existing, roleRepository.findActiveRoleCodes(tenantId, userId)),
                Map.of("status", SysUser.STATUS_DISABLED), null);
        SysUser after = userRepository.findById(tenantId, userId).orElseThrow();
        return toSummary(after, null, roleRepository.findActiveRoleCodes(tenantId, userId));
    }

    private void validateTeam(long tenantId, Long teamId) {
        if (teamId != null && !userRepository.teamExists(tenantId, teamId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求参数校验失败",
                    List.of(new ApiResponse.ErrorDetail("teamId", "团队不存在")));
        }
    }

    private void validateRoleIds(long tenantId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        List<Long> existing = roleRepository.findExistingRoleIds(tenantId, roleIds);
        if (existing.size() != roleIds.stream().distinct().count()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求参数校验失败",
                    List.of(new ApiResponse.ErrorDetail("roleIds", "包含不存在的角色")));
        }
    }

    /**
     * 审计快照白名单：只含非敏感字段，绝不写入 passwordHash。
     */
    private Map<String, Object> userSnapshot(SysUser user, List<String> roles) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", user.getId());
        snapshot.put("username", user.getUsername());
        snapshot.put("displayName", user.getDisplayName());
        snapshot.put("userType", user.getUserType());
        snapshot.put("status", user.getStatus());
        snapshot.put("teamId", user.getTeamId());
        snapshot.put("roles", roles);
        return snapshot;
    }

    private UserSummaryResponse toSummary(UserListRow row, List<String> roles) {
        return new UserSummaryResponse(row.getId(), row.getUsername(), row.getDisplayName(), row.getUserType(),
                row.getStatus(), row.getTeamId(), row.getTeamName(), roles, row.getLastLoginAt(),
                row.getCreatedAt(), row.getVersion());
    }

    private UserSummaryResponse toSummary(SysUser user, String teamName, List<String> roles) {
        return new UserSummaryResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getUserType(),
                user.getStatus(), user.getTeamId(), teamName, roles, user.getLastLoginAt(), user.getCreatedAt(),
                user.getVersion());
    }
}
