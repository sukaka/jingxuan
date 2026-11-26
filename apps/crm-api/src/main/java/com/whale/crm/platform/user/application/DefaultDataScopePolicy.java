package com.whale.crm.platform.user.application;

import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.user.domain.DataScopeGrant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 内置角色到默认行级数据范围（sys_data_scope）的映射策略，
 * 与 docs/01_需求与产品/12_角色权限与数据隔离.md 及开发种子保持一致。
 */
public final class DefaultDataScopePolicy {

    public static final String USER_TYPE_EMPLOYEE = "EMPLOYEE";

    public static final List<String> MANAGED_RESOURCES = List.of(
            DataScopeService.RESOURCE_CUSTOMER,
            DataScopeService.RESOURCE_INSTITUTION);

    private static final Set<String> CUSTOMER_ALL_ROLES = Set.of(
            "ADMIN", "OWNER", "OPERATION", "OPERATION_LEAD", "FINANCE");

    private static final Set<String> INSTITUTION_ALL_ROLES = Set.of(
            "ADMIN", "OWNER", "OPERATION", "OPERATION_LEAD", "FINANCE", "BUSINESS", "MANAGER_LEAD", "MANAGER");

    private static final Set<String> MANAGED_ROLE_CODES = INSTITUTION_ALL_ROLES;

    private DefaultDataScopePolicy() {
    }

    public static boolean hasManagedRole(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return false;
        }
        return roleCodes.stream().anyMatch(MANAGED_ROLE_CODES::contains);
    }

    public static boolean hasCustomerAllRole(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return false;
        }
        return roleCodes.stream().anyMatch(CUSTOMER_ALL_ROLES::contains);
    }

    /**
     * 按用户类型、所属团队与当前生效角色推导默认 customer/institution 数据范围。
     * 机构账号（INSTITUTION）始终返回空列表（由 crm_institution_user 决定）。
     */
    public static List<DataScopeGrant> resolveDefaults(String userType, Long teamId, Collection<String> roleCodes) {
        if (!USER_TYPE_EMPLOYEE.equals(userType) || roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        List<DataScopeGrant> grants = new ArrayList<>(2);
        DataScopeGrant customerGrant = resolveCustomerGrant(teamId, roleCodes);
        if (customerGrant != null) {
            grants.add(customerGrant);
        }
        if (roleCodes.stream().anyMatch(INSTITUTION_ALL_ROLES::contains)) {
            grants.add(new DataScopeGrant(DataScopeService.RESOURCE_INSTITUTION, "ALL", null));
        }
        return List.copyOf(grants);
    }

    public static DataScopeGrant resolveCustomerGrant(Long teamId, Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return null;
        }
        if (hasCustomerAllRole(roleCodes)) {
            return new DataScopeGrant(DataScopeService.RESOURCE_CUSTOMER, "ALL", null);
        }
        if (roleCodes.contains("MANAGER_LEAD")) {
            return teamId != null
                    ? new DataScopeGrant(DataScopeService.RESOURCE_CUSTOMER, "TEAM", teamId)
                    : new DataScopeGrant(DataScopeService.RESOURCE_CUSTOMER, "SELF", null);
        }
        if (roleCodes.contains("MANAGER")) {
            return new DataScopeGrant(DataScopeService.RESOURCE_CUSTOMER, "SELF", null);
        }
        return null;
    }
}
