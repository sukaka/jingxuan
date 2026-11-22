package com.whale.crm.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.institution.application.InstitutionSettlementDayService;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionRepository;
import com.whale.crm.institution.domain.SettlementDayRule;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.settlement.api.dto.CreateSettlementBatchRequest;
import com.whale.crm.settlement.application.SettlementApplicationService;
import com.whale.crm.settlement.domain.SettlementBatch;
import com.whale.crm.settlement.domain.SettlementLine;
import com.whale.crm.settlement.domain.SettlementRedemption;
import com.whale.crm.settlement.domain.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettlementBatchDayApplicationServiceTest {
    private static final CurrentUser FINANCE = new CurrentUser(8, 1, "finance", "财务", "EMPLOYEE", null,
            Set.of("FINANCE"), Set.of("settlement:read", "settlement:write"), Set.of());

    private SettlementRepository repository;
    private InstitutionRepository institutionRepository;
    private InstitutionSettlementDayService settlementDays;
    private SettlementApplicationService service;
    private AtomicReference<SettlementBatch> inserted;

    @BeforeEach
    void setUp() {
        repository = mock(SettlementRepository.class);
        institutionRepository = mock(InstitutionRepository.class);
        settlementDays = mock(InstitutionSettlementDayService.class);
        inserted = new AtomicReference<>();
        DataScopeService dataScopeService = mock(DataScopeService.class);
        when(dataScopeService.scopeFor(any(), eq(DataScopeService.RESOURCE_INSTITUTION))).thenReturn(DataScope.all());
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new SettlementApplicationService(repository, institutionRepository,
                mock(InstitutionAccessSupport.class), mock(FileApplicationService.class), mock(IdempotencyService.class),
                mock(AuditService.class), mock(com.whale.crm.trading.application.OutboxService.class),
                new ObjectMapper().findAndRegisterModules(), transactionManager, settlementDays, dataScopeService);
        when(repository.insertBatch(eq(1L), any())).thenAnswer(invocation -> {
            SettlementBatch draft = invocation.getArgument(1);
            inserted.set(new SettlementBatch(10L, draft.institutionId(), draft.settlementType(), draft.periodKey(),
                    draft.settlementDay(), draft.settlementDaySource(), draft.settlementDate(), draft.status(),
                    draft.amountStatus(), draft.grossFen(), draft.commissionFen(), draft.netPayableFen(),
                    draft.paidFen(), draft.ruleVersion(), draft.verificationEvidence(), draft.createdAt(),
                    draft.updatedAt(), draft.version()));
            return 10L;
        });
    }

    @Test
    void monthlyBatchFreezesEffectiveRuleSourceAndActualDate() {
        InstitutionProfile profile = profile("MONTHLY", "8");
        when(institutionRepository.findProfile(1, 1)).thenReturn(Optional.of(profile));
        when(settlementDays.monthlySnapshot(1, profile, YearMonth.of(2026, 10)))
                .thenReturn(new SettlementDayRule.MonthlySnapshot("8", "INSTITUTION", LocalDate.of(2026, 10, 8)));
        when(repository.lockRedemptions(1, List.of(91L))).thenReturn(List.of(
                new SettlementRedemption(91L, 1L, "POSTED", Instant.parse("2026-10-20T08:00:00Z"))));
        when(repository.findLinesByRedemptions(1L, List.of(91L))).thenReturn(List.of());
        when(repository.findActiveReversalRedemptionIds(1L, List.of(91L))).thenReturn(List.of());
        when(repository.findBatch(1L, 10L)).thenAnswer(invocation -> Optional.of(inserted.get()));

        var detail = service.create(1, FINANCE,
                new CreateSettlementBatchRequest(1L, "MONTHLY", "2026-10", List.of(91L)), "monthly-day");

        ArgumentCaptor<SettlementBatch> captor = ArgumentCaptor.forClass(SettlementBatch.class);
        verify(repository).insertBatch(eq(1L), captor.capture());
        assertThat(captor.getValue().settlementDay()).isEqualTo("8");
        assertThat(captor.getValue().settlementDaySource()).isEqualTo("INSTITUTION");
        assertThat(captor.getValue().settlementDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(detail.batch().settlementDate()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void shanghaiMidnightDeterminesMonthlyServicePeriod() {
        InstitutionProfile profile = profile("MONTHLY", null);
        when(institutionRepository.findProfile(1, 1)).thenReturn(Optional.of(profile));
        when(settlementDays.monthlySnapshot(1, profile, YearMonth.of(2026, 10)))
                .thenReturn(new SettlementDayRule.MonthlySnapshot("LAST_DAY", "DEFAULT", LocalDate.of(2026, 10, 31)));
        when(repository.lockRedemptions(1, List.of(91L))).thenReturn(List.of(
                new SettlementRedemption(91L, 1L, "POSTED", Instant.parse("2026-09-30T16:00:00Z"))));
        when(repository.findLinesByRedemptions(1L, List.of(91L))).thenReturn(List.of());
        when(repository.findActiveReversalRedemptionIds(1L, List.of(91L))).thenReturn(List.of());
        when(repository.findBatch(1L, 10L)).thenAnswer(invocation -> Optional.of(inserted.get()));

        service.create(1, FINANCE,
                new CreateSettlementBatchRequest(1L, "MONTHLY", "2026-10", List.of(91L)), "shanghai-boundary");

        assertThat(inserted.get().settlementDate()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void perRedemptionBatchNeverReadsOrStoresSettlementDay() {
        InstitutionProfile profile = profile("PER_REDEMPTION", "8");
        when(institutionRepository.findProfile(1, 1)).thenReturn(Optional.of(profile));
        when(repository.lockRedemptions(1, List.of(91L))).thenReturn(List.of(
                new SettlementRedemption(91L, 1L, "POSTED", Instant.parse("2026-10-20T08:00:00Z"))));
        when(repository.findLinesByRedemptions(1L, List.of(91L))).thenReturn(List.of());
        when(repository.findActiveReversalRedemptionIds(1L, List.of(91L))).thenReturn(List.of());
        when(repository.findBatch(1L, 10L)).thenAnswer(invocation -> Optional.of(inserted.get()));

        service.create(1, FINANCE,
                new CreateSettlementBatchRequest(1L, "PER_REDEMPTION", "REDEMPTION:91", List.of(91L)), "per-redemption-day");

        verifyNoInteractions(settlementDays);
        assertThat(inserted.get().settlementDay()).isNull();
        assertThat(inserted.get().settlementDaySource()).isNull();
        assertThat(inserted.get().settlementDate()).isNull();
    }

    @Test
    void existingBatchKeepsItsDateWhenConfigurationIsResolvedAgain() {
        SettlementBatch existing = new SettlementBatch(10L, 1L, "MONTHLY", "2026-10", "5", "INSTITUTION",
                LocalDate.of(2026, 10, 5), "DRAFT", "PENDING_VERIFICATION", 0L, 0L, 0L, 0L,
                null, null, null, null, 0L);
        SettlementLine line = mock(SettlementLine.class);
        when(line.settlementBatchId()).thenReturn(10L);
        when(line.redemptionId()).thenReturn(91L);
        when(repository.lockRedemptions(1, List.of(91L))).thenReturn(List.of(
                new SettlementRedemption(91L, 1L, "POSTED", Instant.parse("2026-10-20T08:00:00Z"))));
        when(repository.findLinesByRedemptions(1L, List.of(91L))).thenReturn(List.of(line));
        when(repository.findBatch(1L, 10L)).thenReturn(Optional.of(existing));
        when(repository.findLines(1, 10)).thenReturn(List.of(line));

        var detail = service.create(1, FINANCE,
                new CreateSettlementBatchRequest(1L, "MONTHLY", "2026-10", List.of(91L)), "existing-day");

        assertThat(detail.batch()).isSameAs(existing);
        assertThat(detail.batch().settlementDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        verifyNoInteractions(settlementDays, institutionRepository);
        verify(repository, org.mockito.Mockito.never()).insertBatch(eq(1L), any());
    }

    private InstitutionProfile profile(String settlementType, String settlementDay) {
        return new InstitutionProfile(1L, "INST-1", "合成机构", null, null, null, null, null, null, null, null,
                "ACTIVE", settlementType, settlementDay, new BigDecimal("0.18"), new BigDecimal("0.12"),
                new BigDecimal("0.15"), "manual", false, 0L, false, null, null, null, null, null, null, null, 7L);
    }
}
