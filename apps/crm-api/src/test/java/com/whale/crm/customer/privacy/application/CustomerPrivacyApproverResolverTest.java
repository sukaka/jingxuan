package com.whale.crm.customer.privacy.application;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerPrivacyApproverResolverTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private CustomerScopeSupport scopeSupport;

    private CustomerPrivacyApproverResolver resolver;
    private Customer customer;

    @BeforeEach
    void setUp() {
        resolver = new CustomerPrivacyApproverResolver(userRepository, currentUserService, scopeSupport);
        customer = new Customer();
        customer.setId(100L);
        customer.setTenantId(1L);
        customer.setOwnerStaffId(2L);
        customer.setTeamId(1L);
    }

    @Test
    void prefersDirectSupervisorWhenEligible() {
        SysUser applicantRow = activeEmployee(2L, 1L, 4L);
        CurrentUser applicant = currentUser(2L, 1L, Set.of("MANAGER"), Set.of("customer:privacy:request"));
        CurrentUser supervisor = currentUser(4L, 1L, Set.of("MANAGER_LEAD"), Set.of("customer:privacy:approve"));
        DataScope teamScope = new DataScope(DataScope.ScopeType.TEAM, Set.of(1L), Set.of());

        when(userRepository.findById(1L, 2L)).thenReturn(Optional.of(applicantRow));
        when(currentUserService.loadById(4L)).thenReturn(Optional.of(supervisor));
        when(scopeSupport.scope(supervisor)).thenReturn(teamScope);
        when(scopeSupport.isVisible(teamScope, supervisor, customer)).thenReturn(true);

        var resolved = resolver.resolve(1L, applicant, customer);
        assertThat(resolved).isPresent();
        assertThat(resolved.get().userId()).isEqualTo(4L);
        assertThat(resolved.get().source()).isEqualTo(CustomerPrivacyApproverResolver.SOURCE_DIRECT_SUPERVISOR);
    }

    @Test
    void fallsBackToTeamLeaderThenAdminAndNeverReturnsSelf() {
        SysUser leadRow = activeEmployee(4L, 1L, null);
        CurrentUser leadApplicant = currentUser(4L, 1L, Set.of("MANAGER_LEAD"),
                Set.of("customer:privacy:request", "customer:privacy:approve"));
        CurrentUser admin = currentUser(1L, null, Set.of("ADMIN"), Set.of("customer:privacy:approve"));

        when(userRepository.findById(1L, 4L)).thenReturn(Optional.of(leadRow));
        // Team leader is self -> must be skipped
        when(userRepository.findActiveTeamLeaderId(1L, 1L)).thenReturn(Optional.of(4L));
        when(userRepository.findStewardOptions(1L, List.of("MANAGER_LEAD"), 1L, null))
                .thenReturn(List.of(new UserRepository.StewardOptionRow(4L, "张敏", 1L, "管家一组")));
        when(userRepository.findActiveEmployeeIdsByRole(1L, "ADMIN")).thenReturn(List.of(1L));
        when(currentUserService.loadById(1L)).thenReturn(Optional.of(admin));

        var resolved = resolver.resolve(1L, leadApplicant, customer);
        assertThat(resolved).isPresent();
        assertThat(resolved.get().userId()).isEqualTo(1L);
        assertThat(resolved.get().source()).isEqualTo(CustomerPrivacyApproverResolver.SOURCE_ADMIN_FALLBACK);
    }

    @Test
    void routesOperationUserToUniqueOperationLead() {
        SysUser opRow = activeEmployee(12L, null, null);
        CurrentUser opApplicant = currentUser(12L, null, Set.of("OPERATION"), Set.of("customer:privacy:request"));
        CurrentUser opLead = currentUser(13L, null, Set.of("OPERATION_LEAD"), Set.of("customer:privacy:approve"));
        DataScope allScope = new DataScope(DataScope.ScopeType.ALL, Set.of(), Set.of());

        when(userRepository.findById(1L, 12L)).thenReturn(Optional.of(opRow));
        when(userRepository.findActiveEmployeeIdsByRole(1L, "OPERATION_LEAD")).thenReturn(List.of(13L));
        when(currentUserService.loadById(13L)).thenReturn(Optional.of(opLead));
        when(scopeSupport.scope(opLead)).thenReturn(allScope);
        when(scopeSupport.isVisible(allScope, opLead, customer)).thenReturn(true);

        var resolved = resolver.resolve(1L, opApplicant, customer);
        assertThat(resolved).isPresent();
        assertThat(resolved.get().userId()).isEqualTo(13L);
        assertThat(resolved.get().source()).isEqualTo(CustomerPrivacyApproverResolver.SOURCE_ROLE_SUPERIOR);
    }

    private SysUser activeEmployee(long id, Long teamId, Long supervisorId) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(1L);
        user.setTeamId(teamId);
        user.setSupervisorUserId(supervisorId);
        user.setUserType("EMPLOYEE");
        user.setStatus(SysUser.STATUS_ACTIVE);
        return user;
    }

    private CurrentUser currentUser(long id, Long teamId, Set<String> roles, Set<String> permissions) {
        return new CurrentUser(id, 1L, "u" + id, "员工" + id, "EMPLOYEE", teamId, roles, permissions, Set.of());
    }
}
