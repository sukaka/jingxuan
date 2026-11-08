package com.whale.crm.product.domain;

import java.util.List;

public final class CatalogRules {

    private CatalogRules() {
    }

    public static String rulesStatus(PackageVersion version) {
        if ("CHOOSE_N".equals(version.selectionMode())) {
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
        return "ACTIVE".equals(version.status())
                && "READY".equals(rulesStatus(version))
                && projects.size() > 0
                && projects.stream().allMatch(item -> item.grantCount() != null && item.grantCount() > 0)
                && (!"USE_N".equals(version.selectionMode()) && !"CHOOSE_N".equals(version.selectionMode())
                || version.choiceCount() != null && version.choiceCount() > 0);
    }

    public static boolean canActivate(PackageVersion version, List<PackageProject> projects) {
        if (projects.isEmpty() || projects.stream().anyMatch(item ->
                item.grantCount() == null || item.grantCount() <= 0 || !"ACTIVE".equals(item.projectStatus()))) {
            return false;
        }
        if ("USE_N".equals(version.selectionMode()) || "CHOOSE_N".equals(version.selectionMode())) {
            return version.choiceCount() != null && version.choiceCount() > 0;
        }
        return "FIXED".equals(version.selectionMode());
    }

}
