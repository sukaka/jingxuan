package com.whale.crm.exporting;

import com.whale.crm.exporting.application.ExportScopeService;
import com.whale.crm.exporting.domain.ExportRestriction;
import com.whale.crm.exporting.domain.ExportScope;
import com.whale.crm.exporting.domain.ExportType;
import com.whale.crm.exporting.domain.StoredExportScope;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void institutionIntersectionRetainsOnlyCommonInstitutionsAndTurnsDisjointToNone() {
        ExportScope first = new ExportScope(ExportScope.Kind.INSTITUTION, Set.of(), 10L, Set.of(1L, 2L));
        ExportScope second = new ExportScope(ExportScope.Kind.INSTITUTION, Set.of(), 10L, Set.of(2L, 3L));
        ExportScope disjoint = new ExportScope(ExportScope.Kind.INSTITUTION, Set.of(), 10L, Set.of(3L));
        ExportScope emptyInst = new ExportScope(ExportScope.Kind.INSTITUTION, Set.of(), 10L, Set.of());
        ExportScope all = new ExportScope(ExportScope.Kind.ALL, Set.of(), 10L, Set.of());

        assertThat(first.intersect(second).institutionIds()).containsExactly(2L);
        assertThat(first.intersect(disjoint).kind()).isEqualTo(ExportScope.Kind.NONE);
        assertThat(first.intersect(disjoint).institutionIds()).isEmpty();
        assertThat(emptyInst.intersect(all).kind()).isEqualTo(ExportScope.Kind.NONE);
        assertThat(all.intersect(emptyInst).kind()).isEqualTo(ExportScope.Kind.NONE);
        assertThat(first.intersect(scope(ExportScope.Kind.TEAM, Set.of(1L))).kind())
                .isEqualTo(ExportScope.Kind.NONE);
    }

    @Test
    void executionScopeRejectsDisjointInstitutionScopeWithForbidden() {
        DataScopeService dataScopeService = mock(DataScopeService.class);
        ExportScopeService scopeService = new ExportScopeService(dataScopeService);
        CurrentUser operator = new CurrentUser(10L, 1L, "operator1", "操作员", "INSTITUTION", null,
                Set.of("INSTITUTION_STAFF"), Set.of("export:read"), Set.of(2L));
        when(dataScopeService.scopeFor(operator, DataScopeService.RESOURCE_CUSTOMER))
                .thenReturn(DataScope.institution(Set.of(2L)));
        StoredExportScope stored = new StoredExportScope(
                new ExportScope(ExportScope.Kind.INSTITUTION, Set.of(), 10L, Set.of(1L)),
                null,
                ExportRestriction.none());

        assertThatThrownBy(() -> scopeService.executionScope(operator, ExportType.REDEMPTION, stored))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private ExportScope scope(ExportScope.Kind kind, Set<Long> teamIds) {
        return new ExportScope(kind, teamIds, 4L, Set.of());
    }
}
