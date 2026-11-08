package com.whale.crm.exporting;

import com.whale.crm.exporting.domain.ExportScope;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExportScopeTest {
    @Test
    void intersectionNeverUnionsTeamPermissions() {
        ExportScope teamOne = scope(ExportScope.Kind.TEAM, Set.of(1L));
        ExportScope teamTwo = scope(ExportScope.Kind.TEAM, Set.of(2L));
        ExportScope all = scope(ExportScope.Kind.ALL, Set.of());
        ExportScope self = scope(ExportScope.Kind.SELF, Set.of());

        assertThat(teamOne.intersect(all)).isEqualTo(teamOne);
        assertThat(teamOne.intersect(self)).isEqualTo(self);
        assertThat(teamOne.intersect(teamTwo).kind()).isEqualTo(ExportScope.Kind.TEAM);
        assertThat(teamOne.intersect(teamTwo).teamIds()).isEmpty();
        assertThat(teamOne.intersect(teamTwo).selfId()).isEqualTo(4L);
    }

    @Test
    void institutionIntersectionRetainsOnlyCommonInstitutions() {
        ExportScope first = new ExportScope(ExportScope.Kind.INSTITUTION, Set.of(), 10L, Set.of(1L, 2L));
        ExportScope second = new ExportScope(ExportScope.Kind.INSTITUTION, Set.of(), 10L, Set.of(2L, 3L));

        assertThat(first.intersect(second).institutionIds()).containsExactly(2L);
        assertThat(first.intersect(scope(ExportScope.Kind.TEAM, Set.of(1L))).kind())
                .isEqualTo(ExportScope.Kind.NONE);
    }

    private ExportScope scope(ExportScope.Kind kind, Set<Long> teamIds) {
        return new ExportScope(kind, teamIds, 4L, Set.of());
    }
}
