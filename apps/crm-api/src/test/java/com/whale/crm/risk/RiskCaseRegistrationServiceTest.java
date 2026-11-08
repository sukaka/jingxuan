package com.whale.crm.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.risk.application.RiskCaseRegistrationService;
import com.whale.crm.risk.application.RiskEvidenceMasker;
import com.whale.crm.risk.domain.RiskCase;
import com.whale.crm.risk.domain.RiskCaseHistory;
import com.whale.crm.risk.domain.RiskCaseRepository;
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

    private static RiskCase riskCase(String status, long version) {
        Instant now = Instant.parse("2026-10-26T08:00:00Z");
        return new RiskCase(10L, 1, "RC-TEST", "PAYMENT_MISMATCH", "HIGH", "YOUZAN_SYNC_ISSUE",
                "99", status, 1L, "***", "已核实", now, now, now, version);
    }
}
