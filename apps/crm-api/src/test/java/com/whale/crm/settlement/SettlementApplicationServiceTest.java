package com.whale.crm.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.settlement.api.dto.CreateSettlementBatchRequest;
import com.whale.crm.settlement.api.dto.RecordSettlementPaymentRequest;
import com.whale.crm.settlement.api.dto.VerifySettlementBatchRequest;
import com.whale.crm.settlement.application.SettlementApplicationService;
import com.whale.crm.settlement.domain.SettlementBatch;
import com.whale.crm.settlement.domain.SettlementPayment;
import com.whale.crm.settlement.domain.SettlementRedemption;
import com.whale.crm.settlement.domain.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementApplicationServiceTest {
    private static final CurrentUser FINANCE = new CurrentUser(8, 1, "finance", "财务", "EMPLOYEE", null,
            Set.of("FINANCE"), Set.of("settlement:read", "settlement:write"), Set.of());

    private SettlementRepository repository;
    private InstitutionRepository institutionRepository;
    private FileApplicationService fileOperations;
    private SettlementApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(SettlementRepository.class);
        institutionRepository = mock(InstitutionRepository.class);
        fileOperations = mock(FileApplicationService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new SettlementApplicationService(repository, institutionRepository,
                mock(InstitutionAccessSupport.class), fileOperations, mock(IdempotencyService.class),
                mock(AuditService.class), mock(com.whale.crm.trading.application.OutboxService.class),
                new ObjectMapper().findAndRegisterModules(), transactionManager);
        when(institutionRepository.findProfile(1, 1)).thenReturn(Optional.of(profile()));
    }

    @Test
    void createsPendingBatchFromPostedRedemptionWithRateSnapshotAndNoCalculatedMoney() {
        SettlementRedemption redemption = new SettlementRedemption(91L, 1L, "POSTED", Instant.parse("2026-10-20T08:00:00Z"));
        when(repository.lockRedemptions(eq(1L), eq(List.of(91L)))).thenReturn(List.of(redemption));
        when(repository.findBatchByPeriod(1, 1, "MONTHLY", "2026-10")).thenReturn(Optional.empty())
                .thenReturn(Optional.of(batch("PENDING_VERIFICATION", 0, 0)));

        var detail = service.create(1, FINANCE,
                new CreateSettlementBatchRequest(1L, "MONTHLY", "2026-10", List.of(91L)), "create-1");

        assertThat(detail.batch().amountStatus()).isEqualTo("PENDING_VERIFICATION");
        assertThat(detail.batch().grossFen()).isZero();
        assertThat(detail.batch().netPayableFen()).isZero();
        verify(repository).insertBatch(eq(1L), any());
        verify(repository).insertLine(eq(1L), eq(10L), eq(91L), any());
        verify(repository, never()).updateVerification(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                anyLong(), any(), any());
    }

    @Test
    void rejectsUnpostedRedemptions() {
        when(repository.findBatchByPeriod(1, 1, "MONTHLY", "2026-10")).thenReturn(Optional.empty());
        when(repository.lockRedemptions(1, List.of(91L))).thenReturn(List.of(
                new SettlementRedemption(91L, 1L, "REVERSED", Instant.parse("2026-10-20T08:00:00Z"))));

        assertThatThrownBy(() -> service.create(1, FINANCE,
                new CreateSettlementBatchRequest(1L, "MONTHLY", "2026-10", List.of(91L)), "create-2"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("POSTED");
        verify(repository, never()).insertBatch(eq(1L), any());
    }

    @Test
    void rejectsRedemptionOutsideMonthlyPeriod() {
        when(repository.findBatchByPeriod(1, 1, "MONTHLY", "2026-10")).thenReturn(Optional.empty());
        when(repository.lockRedemptions(1, List.of(91L))).thenReturn(List.of(
                new SettlementRedemption(91L, 1L, "POSTED", Instant.parse("2026-09-30T23:59:59Z"))));

        assertThatThrownBy(() -> service.create(1, FINANCE,
                new CreateSettlementBatchRequest(1L, "MONTHLY", "2026-10", List.of(91L)), "create-wrong-month"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("月份");
        verify(repository, never()).insertBatch(eq(1L), any());
    }

    @Test
    void pendingVerificationCannotBePaid() {
        when(repository.findBatchForUpdate(1, 10)).thenReturn(Optional.of(batch("PENDING_VERIFICATION", 0, 0)));

        assertThatThrownBy(() -> service.pay(1, FINANCE, 10,
                new RecordSettlementPaymentRequest(0L, 100L, "BANK-1", Instant.now(), 55L, "manual-1"), "pay-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("核准");
        verify(fileOperations, never()).requireCleanUsable(anyLong(), any(), anyLong(), any(FileClassification.class), org.mockito.ArgumentMatchers.nullable(Long.class));
        verify(repository, never()).insertPayment(eq(1L), any());
    }

    @Test
    void verificationRequiresExactNonNegativeEquation() {
        when(repository.findBatchForUpdate(1, 10)).thenReturn(Optional.of(batch("PENDING_VERIFICATION", 0, 0)));

        assertThatThrownBy(() -> service.verify(1, FINANCE, 10,
                new VerifySettlementBatchRequest(0L, "RULE-2026-10", 1000L, 101L, 900L, "finance-review"), "verify-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("等式");
        verify(repository, never()).updateVerification(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                anyLong(), any(), any());
    }

    @Test
    void cleanVoucherAllowsInstallmentsButNeverAboveNet() {
        SettlementBatch confirmed = batch("VERIFIED", 0, 1000);
        SettlementBatch partiallyPaid = new SettlementBatch(10L, 1L, "MONTHLY", "2026-10", "PARTIALLY_PAID", "VERIFIED",
                1200L, 200L, 1000L, 400L, "RULE-1", "approved", null, null, 1L);
        when(repository.findBatchForUpdate(1, 10)).thenReturn(Optional.of(confirmed))
                .thenReturn(Optional.of(partiallyPaid));
        when(repository.findBatch(1, 10)).thenReturn(Optional.of(partiallyPaid));
        when(repository.updatePayment(eq(1L), eq(10L), eq(0L), eq(400L), eq("PARTIALLY_PAID"))).thenReturn(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            when(repository.findPaymentByTradeNo(1, "BANK-1")).thenReturn(Optional.of(new SettlementPayment(99L, 10L, 400L, "BANK-1", Instant.now(), 55L, "manual-1", 8L, Instant.now())));
            return null;
        }).when(repository).insertPayment(eq(1L), any());

        service.pay(1, FINANCE, 10, new RecordSettlementPaymentRequest(0L, 400L, "BANK-1",
                Instant.now(), 55L, "manual-1"), "pay-2");
        verify(fileOperations).requireCleanUsable(1, FINANCE, 55, FileClassification.PAYMENT, null);
        verify(repository).insertPayment(eq(1L), any());

        assertThatThrownBy(() -> service.pay(1, FINANCE, 10, new RecordSettlementPaymentRequest(1L, 601L, "BANK-2",
                Instant.now(), 55L, "manual-2"), "pay-3"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("超过");
    }

    private InstitutionProfile profile() {
        return new InstitutionProfile(1L, "INST-1", "合成机构", null, null, null, null, null, null, null, null,
                "ACTIVE", "MONTHLY", new BigDecimal("0.18"), new BigDecimal("0.12"), new BigDecimal("0.15"),
                "manual", false, 0L, false, null, null, null, null, null, null, null, 7L);
    }

    private SettlementBatch batch(String amountStatus, long paid, long net) {
        String status = "VERIFIED".equals(amountStatus) ? "CONFIRMED" : "DRAFT";
        return new SettlementBatch(10L, 1L, "MONTHLY", "2026-10", status, amountStatus,
                0L, 0L, net, paid, null, null, null, null, 0L);
    }
}
