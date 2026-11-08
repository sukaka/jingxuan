package com.whale.crm.platform.security;

import com.whale.crm.platform.user.domain.InstitutionScopeRepository;
import com.whale.crm.platform.user.domain.InstitutionUserScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class InstitutionAccessSupport {
    private final InstitutionScopeRepository repository;

    public InstitutionAccessSupport(InstitutionScopeRepository repository) {
        this.repository = repository;
    }

    public boolean canAccess(long tenantId, CurrentUser user, long institutionId) {
        return user.isInstitutionUser() && scopes(tenantId, user).stream()
                .anyMatch(scope -> scope.institutionId().equals(institutionId));
    }

    public List<Long> visibleInstitutionIds(long tenantId, CurrentUser user) {
        return scopes(tenantId, user).stream().map(InstitutionUserScope::institutionId).toList();
    }

    public Map<Long, String> institutionRoles(long tenantId, CurrentUser user) {
        if (!user.isInstitutionUser()) {
            return Map.of();
        }
        Map<Long, String> roles = new LinkedHashMap<>();
        scopes(tenantId, user).forEach(scope -> roles.put(scope.institutionId(), scope.institutionRole()));
        return roles;
    }

    public void requireStaff(long tenantId, CurrentUser user, long institutionId) {
        requireRole(tenantId, user, institutionId, Set.of("FRONTDESK", "OPERATOR"));
    }

    public void requireOperator(long tenantId, CurrentUser user, long institutionId) {
        requireRole(tenantId, user, institutionId, Set.of("OPERATOR"));
    }

    private void requireRole(long tenantId, CurrentUser user, long institutionId, Set<String> allowedRoles) {
        if (!user.isInstitutionUser()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有本机构授权账号可执行该操作");
        }
        List<InstitutionUserScope> mappings = scopes(tenantId, user).stream()
                .filter(scope -> scope.institutionId().equals(institutionId)).toList();
        if (mappings.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "机构账号未映射到该机构");
        }
        if (mappings.stream().noneMatch(scope -> allowedRoles.contains(scope.institutionRole()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前机构角色无权执行该操作");
        }
    }

    private List<InstitutionUserScope> scopes(long tenantId, CurrentUser user) {
        return repository.findActiveScopes(tenantId, user.userId());
    }
}
