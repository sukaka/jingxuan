package com.whale.crm.product;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.product.domain.CatalogRules;
import com.whale.crm.product.domain.PackageOptionSelection;
import com.whale.crm.product.domain.PackageProject;
import com.whale.crm.product.domain.PackageSelectionGroup;
import com.whale.crm.product.domain.PackageSelectionRules;
import com.whale.crm.product.domain.PackageVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageSelectionRulesTest {

    @Test
    void groupedChooseNSelectsEachGroupIndependentlyAndKeepsReferencePrices() {
        List<PackageSelectionGroup> groups = List.of(group("A", 2, 2, 1), group("B", 3, 3, 2));
        List<PackageProject> projects = List.of(
                project(101, "A", 80000, 1), project(102, "A", 40000, 1),
                project(103, "A", 30000, 1), project(104, "A", 20000, 1),
                project(201, "B", 10000, 2), project(202, "B", 11000, 1),
                project(203, "B", 12000, 1), project(204, "B", 13000, 1));
        List<PackageOptionSelection> selections = List.of(
                new PackageOptionSelection("A", 102L), new PackageOptionSelection("A", 101L),
                new PackageOptionSelection("B", 201L), new PackageOptionSelection("B", 202L),
                new PackageOptionSelection("B", 203L));

        List<PackageProject> selected = PackageSelectionRules.select("CHOOSE_N", groups, projects, selections);

        assertThat(selected).extracting(PackageProject::projectId)
                .containsExactly(101L, 102L, 201L, 202L, 203L);
        assertThat(selected).extracting(PackageProject::referencePriceFen)
                .containsExactly(80000L, 40000L, 10000L, 11000L, 12000L);
        assertThat(PackageSelectionRules.referenceAmountFen(selected)).isEqualTo(163000L);
        assertThat(CatalogRules.rulesStatus(version("CHOOSE_N"), groups)).isEqualTo("READY");
        assertThat(CatalogRules.entitlementIssuable(version("CHOOSE_N"), projects, groups)).isTrue();
    }

    @Test
    void groupedChooseNRejectsWrongCountsDuplicatesAndOptionsOutsideTheirGroup() {
        List<PackageSelectionGroup> groups = List.of(group("A", 2, 2, 1));
        List<PackageProject> projects = List.of(project(101, "A", 80000, 1),
                project(102, "A", 40000, 1));

        assertThatThrownBy(() -> PackageSelectionRules.select("CHOOSE_N", groups, projects,
                List.of(new PackageOptionSelection("A", 101L))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("至少选择 2 项");
        assertThatThrownBy(() -> PackageSelectionRules.select("CHOOSE_N", groups, projects,
                List.of(new PackageOptionSelection("A", 101L), new PackageOptionSelection("A", 101L))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重复");
        assertThatThrownBy(() -> PackageSelectionRules.select("CHOOSE_N", groups, projects,
                List.of(new PackageOptionSelection("A", 101L), new PackageOptionSelection("A", 103L))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不属于选项组");
    }

    @Test
    void legacyModesKeepTheirExistingWholeTemplateBehavior() {
        List<PackageProject> projects = List.of(project(101, "MAIN", 80000, 2));

        assertThat(PackageSelectionRules.select("USE_N", List.of(), projects, null))
                .containsExactlyElementsOf(projects);
        assertThat(CatalogRules.rulesStatus(version("CHOOSE_N"), List.of())).isEqualTo("PENDING_Q01");
        assertThat(CatalogRules.entitlementIssuable(version("CHOOSE_N"), projects, List.of())).isFalse();
    }

    private PackageVersion version(String mode) {
        return new PackageVersion(1L, 2L, 1, mode, 0, null, "ACTIVE", null, 0L);
    }

    private PackageSelectionGroup group(String code, int min, int max, int sortOrder) {
        return new PackageSelectionGroup(null, 1L, code, code + " 组", min, max, sortOrder, 0L);
    }

    private PackageProject project(long id, String group, long referencePrice, int grantCount) {
        return new PackageProject(null, 1L, id, "PRJ-" + id, "项目 " + id, "TEST", referencePrice,
                "ACTIVE", grantCount, 1, 0L, group, referencePrice, 0L);
    }
}
