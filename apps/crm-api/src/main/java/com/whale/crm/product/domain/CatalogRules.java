package com.whale.crm.product.domain;

import com.whale.crm.platform.web.BusinessException;

import java.util.List;

public final class CatalogRules {

    private CatalogRules() {
    }

    public static String rulesStatus(PackageVersion version) {
        return rulesStatus(version, List.of());
    }

    public static String rulesStatus(PackageVersion version, List<PackageSelectionGroup> groups) {
        if ("CHOOSE_N".equals(version.selectionMode()) && groups.isEmpty()) {
            return "PENDING_Q01";
        }
        return "READY";
    }

    public static String allocationStatus(List<PackageProject> projects) {
        if (projects.size() > 1 && projects.stream().anyMatch(item ->
                item.allocationPriceFen() == null || item.allocationPriceFen() <= 0)) {
            return "PENDING_Q02";
        }
        return "CONFIGURED";
    }

    public static boolean entitlementIssuable(PackageVersion version, List<PackageProject> projects) {
        return entitlementIssuable(version, projects, List.of());
    }

    public static boolean entitlementIssuable(PackageVersion version, List<PackageProject> projects,
                                              List<PackageSelectionGroup> groups) {
        if (!"ACTIVE".equals(version.status()) || projects.isEmpty()
                || projects.stream().anyMatch(item -> item.grantCount() == null || item.grantCount() <= 0)) {
            return false;
        }
        if ("CHOOSE_N".equals(version.selectionMode())) {
            return !groups.isEmpty() && validGroups(groups, projects);
        }
        return !groups.isEmpty() ? false
                : !"USE_N".equals(version.selectionMode()) || version.choiceCount() != null && version.choiceCount() > 0;
    }

    public static boolean canActivate(PackageVersion version, List<PackageProject> projects) {
        return canActivate(version, projects, List.of());
    }

    public static boolean canActivate(PackageVersion version, List<PackageProject> projects,
                                      List<PackageSelectionGroup> groups) {
        if (projects.isEmpty() || projects.stream().anyMatch(item ->
                item.grantCount() == null || item.grantCount() <= 0 || !"ACTIVE".equals(item.projectStatus()))) {
            return false;
        }
        if ("CHOOSE_N".equals(version.selectionMode())) {
            return groups.isEmpty() ? version.choiceCount() != null && version.choiceCount() > 0
                    : validGroups(groups, projects);
        }
        if ("USE_N".equals(version.selectionMode())) {
            return groups.isEmpty() && version.choiceCount() != null && version.choiceCount() > 0;
        }
        return "FIXED".equals(version.selectionMode()) && groups.isEmpty();
    }

    private static boolean validGroups(List<PackageSelectionGroup> groups, List<PackageProject> projects) {
        try {
            PackageSelectionRules.validateConfiguration(groups, projects);
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }
}
