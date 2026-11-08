package com.whale.crm.product;

import com.whale.crm.product.domain.CatalogRules;
import com.whale.crm.product.domain.PackageProject;
import com.whale.crm.product.domain.PackageVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogRulesTest {

    @Test
    void chooseNWithoutConfirmedRulesIsOnlyATemplate() {
        PackageVersion version = version("CHOOSE_N", null, "ACTIVE");
        List<PackageProject> projects = List.of(project(1), project(2), project(3));

        assertThat(CatalogRules.rulesStatus(version)).isEqualTo("PENDING_Q01");
        assertThat(CatalogRules.entitlementIssuable(version, projects)).isFalse();
        assertThat(CatalogRules.canActivate(version, projects)).isTrue();
        assertThat(CatalogRules.allocationStatus(projects)).isEqualTo("PENDING_Q02");
    }

    @Test
    void useNWithProjectsCanIssueOnlyWhenActive() {
        List<PackageProject> projects = List.of(project(3));

        assertThat(CatalogRules.entitlementIssuable(version("USE_N", null, "DRAFT"), projects)).isFalse();
        assertThat(CatalogRules.entitlementIssuable(version("USE_N", null, "ACTIVE"), projects)).isTrue();
        assertThat(CatalogRules.rulesStatus(version("USE_N", null, "ACTIVE"))).isEqualTo("READY");
    }

    @Test
    void choiceTextCannotBypassQ01OrAllocationReview() {
        PackageVersion version = version("CHOOSE_N", "CONFIRMED:allowRepeat=false;perProjectCount=1", "ACTIVE");
        List<PackageProject> projects = List.of(project(1), project(1));

        assertThat(CatalogRules.entitlementIssuable(version, projects)).isFalse();
        assertThat(CatalogRules.rulesStatus(version)).isEqualTo("PENDING_Q01");
        assertThat(CatalogRules.allocationStatus(projects)).isEqualTo("PENDING_Q02");
    }

    private PackageVersion version(String mode, String rules, String status) {
        return new PackageVersion(1L, 2L, 1, mode, 3, rules, status, null, 0L);
    }

    private PackageProject project(int grantCount) {
        return new PackageProject(null, 1L, 101L, "PRJ", "项目", "TEST", 100L, "ACTIVE",
                grantCount, 1, 0L, "MAIN", 0L);
    }
}
