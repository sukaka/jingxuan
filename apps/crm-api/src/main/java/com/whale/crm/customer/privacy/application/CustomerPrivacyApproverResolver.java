package com.whale.crm.customer.privacy.application;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 客户隐私查看申请的上级审批人解析器。
 * 解析优先级（严格禁止自审；候选人必须为同租户 ACTIVE 内部员工、持 customer:privacy:approve、且对目标客户可见或为 ADMIN）：
 * 1. DIRECT_SUPERVISOR: sys_user.supervisor_user_id
 * 2. TEAM_LEADER: 所属团队 sys_team.leader_user_id
 * 3. TEAM_MANAGER_LEAD: 所属团队内唯一 ACTIVE MANAGER_LEAD（排除申请人本人）
 * 4. ROLE_SUPERIOR: OPERATION 角色路由至唯一 ACTIVE OPERATION_LEAD
 * 5. ADMIN_FALLBACK: 兜底路由至启用状态的 ADMIN（按 ID 升序，排除本人）；若无其他 ADMIN 再尝试启用状态的 OWNER
 */
@Component
public class CustomerPrivacyApproverResolver {

    public static final String PERMISSION_APPROVE = "customer:privacy:approve";
    public static final String SOURCE_DIRECT_SUPERVISOR = "DIRECT_SUPERVISOR";
    public static final String SOURCE_TEAM_LEADER = "TEAM_LEADER";
    public static final String SOURCE_TEAM_MANAGER_LEAD = "TEAM_MANAGER_LEAD";
    public static final String SOURCE_ROLE_SUPERIOR = "ROLE_SUPERIOR";
    public static final String SOURCE_ADMIN_FALLBACK = "ADMIN_FALLBACK";

    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final CustomerScopeSupport scopeSupport;

    public CustomerPrivacyApproverResolver(UserRepository userRepository,
                                           CurrentUserService currentUserService,
                                           CustomerScopeSupport scopeSupport) {
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.scopeSupport = scopeSupport;
    }

    public Optional<ResolvedApprover> resolve(long tenantId, CurrentUser applicant, Customer customer) {
        SysUser applicantUser = userRepository.findById(tenantId, applicant.userId()).orElse(null);
        if (applicantUser == null || !applicantUser.isActive()) {
            return Optional.empty();
        }

        // 1. 直属上级
        if (applicantUser.getSupervisorUserId() != null) {
            Optional<ResolvedApprover> direct = tryCandidate(tenantId, applicantUser.getSupervisorUserId(),
                    applicant.userId(), customer, SOURCE_DIRECT_SUPERVISOR);
            if (direct.isPresent()) {
                return direct;
            }
        }

        // 2. 团队负责人 + 3. 团队内唯一管家主管
        Long teamId = applicantUser.getTeamId();
        if (teamId != null) {
            Optional<Long> teamLeaderId = userRepository.findActiveTeamLeaderId(tenantId, teamId);
            if (teamLeaderId.isPresent()) {
                Optional<ResolvedApprover> leader = tryCandidate(tenantId, teamLeaderId.get(),
                        applicant.userId(), customer, SOURCE_TEAM_LEADER);
                if (leader.isPresent()) {
                    return leader;
                }
            }
            List<UserRepository.StewardOptionRow> teamLeads = userRepository
                    .findStewardOptions(tenantId, List.of("MANAGER_LEAD"), teamId, null)
                    .stream()
                    .filter(row -> !Objects.equals(row.id(), applicant.userId()))
                    .toList();
            if (teamLeads.size() == 1) {
                Optional<ResolvedApprover> lead = tryCandidate(tenantId, teamLeads.getFirst().id(),
                        applicant.userId(), customer, SOURCE_TEAM_MANAGER_LEAD);
                if (lead.isPresent()) {
                    return lead;
                }
            }
        }

        // 4. 运营角色优先路由至唯一运营主管
        if (applicant.roles().contains("OPERATION") && !applicant.roles().contains("OPERATION_LEAD")) {
            List<Long> opLeads = userRepository.findActiveEmployeeIdsByRole(tenantId, "OPERATION_LEAD")
                    .stream()
                    .filter(id -> !Objects.equals(id, applicant.userId()))
                    .toList();
            if (opLeads.size() == 1) {
                Optional<ResolvedApprover> opLead = tryCandidate(tenantId, opLeads.getFirst(),
                        applicant.userId(), customer, SOURCE_ROLE_SUPERIOR);
                if (opLead.isPresent()) {
                    return opLead;
                }
            }
        }

        // 5. 兜底至其他系统管理员 / 经营负责人
        for (Long adminId : userRepository.findActiveEmployeeIdsByRole(tenantId, "ADMIN")) {
            Optional<ResolvedApprover> admin = tryCandidate(tenantId, adminId, applicant.userId(),
                    customer, SOURCE_ADMIN_FALLBACK);
            if (admin.isPresent()) {
                return admin;
            }
        }
        for (Long ownerId : userRepository.findActiveEmployeeIdsByRole(tenantId, "OWNER")) {
            Optional<ResolvedApprover> owner = tryCandidate(tenantId, ownerId, applicant.userId(),
                    customer, SOURCE_ADMIN_FALLBACK);
            if (owner.isPresent()) {
                return owner;
            }
        }
        return Optional.empty();
    }

    private Optional<ResolvedApprover> tryCandidate(long tenantId, Long candidateId, long applicantUserId,
                                                    Customer customer, String source) {
        if (candidateId == null || candidateId.longValue() == applicantUserId) {
            return Optional.empty();
        }
        Optional<CurrentUser> loaded = currentUserService.loadById(candidateId)
                .filter(u -> u.tenantId() == tenantId)
                .filter(u -> !u.isInstitutionUser())
                .filter(u -> u.permissions().contains(PERMISSION_APPROVE));
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        CurrentUser candidate = loaded.get();
        boolean canAccessCustomer = candidate.roles().contains("ADMIN")
                || scopeSupport.isVisible(scopeSupport.scope(candidate), candidate, customer);
        if (!canAccessCustomer) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedApprover(candidate.userId(), candidate.displayName(), source));
    }

    public record ResolvedApprover(Long userId, String displayName, String source) {
    }
}
