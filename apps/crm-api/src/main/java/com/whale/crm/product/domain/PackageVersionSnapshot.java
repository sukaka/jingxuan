package com.whale.crm.product.domain;

import java.util.List;

public record PackageVersionSnapshot(Long id, Long packageId, String packageCode, String packageName,
                                     Integer versionNo, String selectionMode, Integer choiceCount,
                                     String selectionRules, String status, Long version,
                                     String rulesStatus, String allocationStatus, boolean entitlementIssuable,
                                     List<PackageProject> projects, List<Sku> skus,
                                     List<Institution> institutions) {
    public PackageVersionSnapshot {
        projects = projects == null ? null : List.copyOf(projects);
        skus = skus == null ? null : List.copyOf(skus);
        institutions = institutions == null ? null : List.copyOf(institutions);
    }
}
