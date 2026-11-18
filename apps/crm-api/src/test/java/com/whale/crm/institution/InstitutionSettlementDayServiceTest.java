package com.whale.crm.institution;

import com.whale.crm.institution.application.InstitutionSettlementDayService;
import com.whale.crm.institution.application.InstitutionSupport;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionSettlementConfig;
import com.whale.crm.institution.domain.InstitutionSettlementConfigRepository;
import com.whale.crm.platform.audit.application.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstitutionSettlementDayServiceTest {
    @Test
    void missingTenantConfigUsesBuiltInLastDayWithoutCreatingAFormalRow() {
        InstitutionSettlementConfigRepository repository = mock(InstitutionSettlementConfigRepository.class);
        when(repository.find(42)).thenReturn(Optional.empty());
        InstitutionSettlementDayService service = new InstitutionSettlementDayService(repository,
                mock(InstitutionSupport.class), mock(AuditService.class), mock(PlatformTransactionManager.class));
        InstitutionProfile profile = profile(null);

        assertThat(service.get(42).defaultSettlementDay()).isEqualTo("LAST_DAY");
        assertThat(service.get(42).configured()).isFalse();
        assertThat(service.monthlySnapshot(42, profile, YearMonth.of(2026, 2)).settlementDate())
                .isEqualTo(LocalDate.of(2026, 2, 28));
        verify(repository, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tenantDefaultAndInstitutionOverrideAreResolvedIndependently() {
        InstitutionSettlementConfigRepository repository = mock(InstitutionSettlementConfigRepository.class);
        when(repository.find(42)).thenReturn(Optional.of(new InstitutionSettlementConfig(7L, 42, "10",
                1L, null, null, 2)));
        InstitutionSettlementDayService service = new InstitutionSettlementDayService(repository,
                mock(InstitutionSupport.class), mock(AuditService.class), mock(PlatformTransactionManager.class));

        assertThat(service.monthlySnapshot(42, profile(null), YearMonth.of(2026, 11)).settlementDate())
                .isEqualTo(LocalDate.of(2026, 11, 10));
        var custom = service.monthlySnapshot(42, profile("LAST_DAY"), YearMonth.of(2026, 11));
        assertThat(custom.source()).isEqualTo("INSTITUTION");
        assertThat(custom.settlementDate()).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    private InstitutionProfile profile(String settlementDay) {
        return new InstitutionProfile(1L, "INST-1", "合成机构", null, null, null, null, null, null, null, null,
                "ACTIVE", "MONTHLY", settlementDay, null, null, null, null, false, 0L, false, null, null,
                null, null, null, null, null, 0L);
    }
}
