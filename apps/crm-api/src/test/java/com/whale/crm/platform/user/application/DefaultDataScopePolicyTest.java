package com.whale.crm.platform.user.application;

import com.whale.crm.platform.user.domain.DataScopeGrant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultDataScopePolicyTest {

    @Test
    void managerGetsSelfCustomerAndAllInstitution() {
        assertThat(DefaultDataScopePolicy.resolveDefaults("EMPLOYEE", 1L, List.of("MANAGER")))
                .containsExactly(
                        new DataScopeGrant("customer", "SELF", null),
                        new DataScopeGrant("institution", "ALL", null));
    }

    @Test
    void managerLeadGetsTeamWhenAssignedAndSelfWhenUnassigned() {
        assertThat(DefaultDataScopePolicy.resolveDefaults("EMPLOYEE", 2L, List.of("MANAGER_LEAD")))
                .containsExactly(
                        new DataScopeGrant("customer", "TEAM", 2L),
                        new DataScopeGrant("institution", "ALL", null));
        assertThat(DefaultDataScopePolicy.resolveDefaults("EMPLOYEE", null, List.of("MANAGER_LEAD")))
                .containsExactly(
                        new DataScopeGrant("customer", "SELF", null),
                        new DataScopeGrant("institution", "ALL", null));
    }

    @Test
    void companyWideRolesGetAllForCustomerAndInstitution() {
        for (String role : List.of("ADMIN", "OWNER", "OPERATION", "OPERATION_LEAD", "FINANCE")) {
            assertThat(DefaultDataScopePolicy.resolveDefaults("EMPLOYEE", 1L, List.of(role)))
                    .containsExactly(
                            new DataScopeGrant("customer", "ALL", null),
                            new DataScopeGrant("institution", "ALL", null));
        }
    }

    @Test
    void businessGetsInstitutionAllOnlyWithoutCustomerScope() {
        assertThat(DefaultDataScopePolicy.resolveDefaults("EMPLOYEE", null, List.of("BUSINESS")))
                .containsExactly(new DataScopeGrant("institution", "ALL", null));
    }

    @Test
    void multipleRolesResolveByHighestPriority() {
        assertThat(DefaultDataScopePolicy.resolveDefaults("EMPLOYEE", 3L, List.of("MANAGER", "ADMIN")))
                .containsExactly(
                        new DataScopeGrant("customer", "ALL", null),
                        new DataScopeGrant("institution", "ALL", null));
        assertThat(DefaultDataScopePolicy.resolveDefaults("EMPLOYEE", 3L, List.of("MANAGER", "MANAGER_LEAD")))
                .containsExactly(
                        new DataScopeGrant("customer", "TEAM", 3L),
                        new DataScopeGrant("institution", "ALL", null));
    }

    @Test
    void institutionAndCustomOnlyUsersGetNoDefaultGrants() {
        assertThat(DefaultDataScopePolicy.resolveDefaults("INSTITUTION", null, List.of("INSTITUTION_STAFF")))
                .isEmpty();
        assertThat(DefaultDataScopePolicy.resolveDefaults("INSTITUTION", 1L, List.of("MANAGER")))
                .isEmpty();
        assertThat(DefaultDataScopePolicy.resolveDefaults("EMPLOYEE", 1L, List.of("CUSTOM_ROLE")))
                .isEmpty();
        assertThat(DefaultDataScopePolicy.resolveDefaults("EMPLOYEE", 1L, List.of()))
                .isEmpty();
    }
}
