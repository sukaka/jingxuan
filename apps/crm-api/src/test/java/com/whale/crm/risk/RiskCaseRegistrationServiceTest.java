package com.whale.crm.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.risk.application.RiskCaseRegistrationService;
import com.whale.crm.risk.application.RiskEvidenceMasker;
import com.whale.crm.risk.domain.RiskCase;
import com.whale.crm.risk.domain.RiskCaseHistory;
import com.whale.crm.risk.domain.RiskCaseRepository;
import com.whale.crm.risk.domain.RiskResolvedIds;
import com.whale.crm.risk.domain.RiskSourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskCaseRegistrationServiceTest {
    @Mock RiskCaseRepository repository;
    @Mock AuditService auditService;

    private RiskCaseRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RiskCaseRegistrationService(repository,
                new RiskEvidenceMasker(new ObjectMapper().findAndRegisterModules()), auditService);
    }

    @Test
    void registrationStoresOnlyMaskedEvidenceAndAppendsHistoryAndAudit() {
        RiskSourceRecord source = new RiskSourceRecord(1, "PAYMENT_MISMATCH", "HIGH", "YOUZAN_SYNC_ISSUE",
                "99", "{\"buyerName\":\"合成姓名\",\"mobile\":\"13800138000\",\"detail\":\"支付金额不一致\"}");
        RiskCase created = riskCase("OPEN", 0);
        when(repository.findBySource(1, "YOUZAN_SYNC_ISSUE", "99", "PAYMENT_MISMATCH"))
                .thenReturn(Optional.empty(), Optional.of(created));

        RiskCase result = service.register(source, null);

        assertThat(result.id()).isEqualTo(10);
        ArgumentCaptor<RiskCase> inserted = ArgumentCaptor.forClass(RiskCase.class);
        verify(repository).insert(inserted.capture());
        assertThat(inserted.getValue().evidence()).doesNotContain("13800138000", "合成姓名");
        assertThat(inserted.getValue().evidence()).contains("\"mobile\":\"***\"");
        ArgumentCaptor<RiskCaseHistory> history = ArgumentCaptor.forClass(RiskCaseHistory.class);
        verify(repository).insertHistory(history.capture());
        assertThat(history.getValue().action()).isEqualTo("REGISTER");
        assertThat(history.getValue().fromStatus()).isNull();
        assertThat(history.getValue().toStatus()).isEqualTo("OPEN");
        verify(auditService).record(eq(1L), eq(null), eq("RISK_CASE_REGISTER"), eq("RISK_CASE"), eq("10"),
                eq(null), any(), anyString());
    }

    @Test
    void manuallyClosedHistoryIsNeverReopenedOrDuplicatedBySourceRetry() {
        RiskCase closed = riskCase("CLOSED", 3);
        when(repository.findBySource(1, "YOUZAN_SYNC_ISSUE", "99", "PAYMENT_MISMATCH"))
                .thenReturn(Optional.of(closed));
        RiskSourceRecord source = new RiskSourceRecord(1, "PAYMENT_MISMATCH", "HIGH", "YOUZAN_SYNC_ISSUE",
                "99", "新的同步尝试仍报告同一来源异常 13800138000");

        RiskCase result = service.register(source, 1L);

        assertThat(result.status()).isEqualTo("CLOSED");
        assertThat(result.version()).isEqualTo(3);
        verify(repository, never()).insert(any());
        verify(repository, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                any(), any(), any());
        verify(repository, never()).insertHistory(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void duplicateKeyRaceReturnsCurrentExistingCaseWithoutDuplicatingHistory() {
        RiskCase existing = riskCase("CLOSED", 3);
        when(repository.findBySource(1, "YOUZAN_SYNC_ISSUE", "99", "PAYMENT_MISMATCH"))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate source")).when(repository).insert(any());
        when(repository.findBySourceForUpdate(1, "YOUZAN_SYNC_ISSUE", "99", "PAYMENT_MISMATCH"))
                .thenReturn(Optional.of(existing));
        RiskSourceRecord source = new RiskSourceRecord(1, "PAYMENT_MISMATCH", "HIGH", "YOUZAN_SYNC_ISSUE",
                "99", "并发重复来源");

        RiskCase result = service.register(source, null);

        assertThat(result.status()).isEqualTo("CLOSED");
        verify(repository).findBySourceForUpdate(1, "YOUZAN_SYNC_ISSUE", "99", "PAYMENT_MISMATCH");
        verify(repository, never()).insertHistory(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void registrationResolvesCustomerAndOrderAcrossFiveSourceTypesAndSkipsUnsupported() {
        when(repository.resolveOrderLink(1L, 401L)).thenReturn(Optional.of(new RiskResolvedIds(401L, 101L)));
        when(repository.resolveRefundLink(1L, 431L)).thenReturn(Optional.of(new RiskResolvedIds(401L, 101L)));
        when(repository.resolveEntitlementLink(1L, 421L)).thenReturn(Optional.of(new RiskResolvedIds(401L, 101L)));
        when(repository.resolveRedemptionLink(1L, 451L)).thenReturn(Optional.of(new RiskResolvedIds(null, 101L)));

        for (var entry : java.util.List.of(
                new RiskSourceRecord(1L, "ORDER_PAYMENT_CASH", "HIGH", "ORDER", "401", "{}"),
                new RiskSourceRecord(1L, "REFUND_ALLOCATION", "HIGH", "REFUND", "431", "{}"),
                new RiskSourceRecord(1L, "ENTITLEMENT_LEDGER", "HIGH", "ENTITLEMENT", "421", "{}"),
                new RiskSourceRecord(1L, "UNBATCHED_REDEMPTION", "MEDIUM", "REDEMPTION", "451", "{}"),
                new RiskSourceRecord(1L, "SETTLEMENT_PAYMENT", "HIGH", "SETTLEMENT_BATCH", "123", "{}"))) {
            RiskCase created = new RiskCase(10L, 1L, "RC-1", entry.riskType(), entry.severity(),
                    entry.sourceType(), entry.sourceRef(), null, null, "OPEN", null, "{}", null, null,
                    Instant.now(), Instant.now(), 0L);
            when(repository.findBySource(1L, entry.sourceType(), entry.sourceRef(), entry.riskType()))
                    .thenReturn(Optional.empty(), Optional.of(created));
            service.register(entry, null);
        }

        ArgumentCaptor<RiskCase> captor = ArgumentCaptor.forClass(RiskCase.class);
        verify(repository, org.mockito.Mockito.times(5)).insert(captor.capture());
        var inserted = captor.getAllValues();
        assertThat(inserted.get(0).customerId()).isEqualTo(101L);
        assertThat(inserted.get(0).orderId()).isEqualTo(401L);
        assertThat(inserted.get(1).customerId()).isEqualTo(101L);
        assertThat(inserted.get(1).orderId()).isEqualTo(401L);
        assertThat(inserted.get(2).customerId()).isEqualTo(101L);
        assertThat(inserted.get(2).orderId()).isEqualTo(401L);
        assertThat(inserted.get(3).customerId()).isEqualTo(101L);
        assertThat(inserted.get(3).orderId()).isNull();
        assertThat(inserted.get(4).customerId()).isNull();
        assertThat(inserted.get(4).orderId()).isNull();
    }

    @Test
    void existingOpenUnlinkedCaseIsBackfilledWithoutHistoryOrAuditWhileClosedRemainsImmutable() {
        RiskCase openUnlinked = riskCase("OPEN", 2);
        RiskCase openBackfilled = new RiskCase(10L, 1L, "RC-TEST", "PAYMENT_MISMATCH", "HIGH", "YOUZAN_SYNC_ISSUE",
                "99", 101L, null, "OPEN", 1L, "***", "已核实",
                Instant.parse("2026-10-26T08:00:00Z"), Instant.parse("2026-10-26T08:00:00Z"),
                Instant.parse("2026-10-26T08:00:00Z"), 2L);
        when(repository.findBySource(1L, "YOUZAN_SYNC_ISSUE", "99", "PAYMENT_MISMATCH"))
                .thenReturn(Optional.of(openUnlinked));
        when(repository.resolveYouzanIssueLink(1L, 99L)).thenReturn(Optional.of(new RiskResolvedIds(null, 101L)));
        when(repository.findById(1L, 10L)).thenReturn(Optional.of(openBackfilled));

        var outcome = service.registerOutcome(
                new RiskSourceRecord(1L, "PAYMENT_MISMATCH", "HIGH", "YOUZAN_SYNC_ISSUE", "99", "{}"), null);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.riskCase().customerId()).isEqualTo(101L);
        assertThat(outcome.riskCase().version()).isEqualTo(2L);
        verify(repository).backfillLinkIfAbsent(1L, 10L, 101L, null);
        verify(repository, never()).insertHistory(any());
        verifyNoInteractions(auditService);

        org.mockito.Mockito.reset(repository, auditService);
        RiskCase closedUnlinked = riskCase("CLOSED", 3);
        when(repository.findBySource(1L, "YOUZAN_SYNC_ISSUE", "99", "PAYMENT_MISMATCH"))
                .thenReturn(Optional.of(closedUnlinked));
        var closedOutcome = service.registerOutcome(
                new RiskSourceRecord(1L, "PAYMENT_MISMATCH", "HIGH", "YOUZAN_SYNC_ISSUE", "99", "{}"), null);
        assertThat(closedOutcome.created()).isFalse();
        verify(repository, never()).backfillLinkIfAbsent(anyLong(), anyLong(), any(), any());
        verify(repository, never()).resolveYouzanIssueLink(anyLong(), anyLong());
    }

    private static RiskCase riskCase(String status, long version) {
        Instant now = Instant.parse("2026-10-26T08:00:00Z");
        return new RiskCase(10L, 1, "RC-TEST", "PAYMENT_MISMATCH", "HIGH", "YOUZAN_SYNC_ISSUE",
                "99", status, 1L, "***", "已核实", now, now, now, version);
    }
}
