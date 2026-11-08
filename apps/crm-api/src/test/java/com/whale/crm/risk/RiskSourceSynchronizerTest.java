package com.whale.crm.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.youzan.application.YouzanManagementService;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.risk.application.RiskCaseRegistrationService;
import com.whale.crm.risk.application.RiskSourceSynchronizer;
import com.whale.crm.risk.domain.RiskSourceRecord;
import com.whale.crm.settlement.application.ReconciliationService;
import com.whale.crm.settlement.api.dto.ReconciliationResponse;
import com.whale.crm.settlement.domain.ReconciliationIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskSourceSynchronizerTest {
    private static final CurrentUser ADMIN = new CurrentUser(1, 1, "admin", "管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("risk:read"), Set.of());

    @Mock YouzanManagementService youzanService;
    @Mock ReconciliationService reconciliationService;
    @Mock RiskCaseRegistrationService registrationService;

    private RiskSourceSynchronizer synchronizer;

    @BeforeEach
    void setUp() {
        synchronizer = new RiskSourceSynchronizer(youzanService, reconciliationService, registrationService,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void importsOnlyRealUnresolvedSourcesWithStableReferencesAndIntegerFen() {
        YouzanSyncIssue open = new YouzanSyncIssue(91L, 81L, "PAYMENT_MISMATCH", "OPEN",
                "支付异常，手机号13800138000", 1, null, null, Instant.now(), null, null, 0L);
        YouzanSyncIssue resolved = new YouzanSyncIssue(92L, 82L, "MAPPING_FAILED", "RESOLVED",
                "历史映射异常", 1, null, null, Instant.now(), Instant.now(), 1L, 1L);
        when(youzanService.issues(1, null, 1, 100)).thenReturn(new PageResponse<>(List.of(open, resolved), 2, 1, 100));
        ReconciliationIssue cash = new ReconciliationIssue("ORDER_PAYMENT_CASH", 71L, null,
                null, null, 1000L, 900L, "订单实付与支付流水不一致");
        when(reconciliationService.reconcile(eq(1L), eq(ADMIN), eq(null)))
                .thenReturn(new ReconciliationResponse(Instant.now(), 1, List.of(cash)));

        synchronizer.sync(ADMIN);

        ArgumentCaptor<RiskSourceRecord> sources = ArgumentCaptor.forClass(RiskSourceRecord.class);
        verify(registrationService, times(2)).register(sources.capture(), eq(null));
        assertThat(sources.getAllValues()).extracting(RiskSourceRecord::sourceRef).containsExactly("91", "71");
        RiskSourceRecord youzan = sources.getAllValues().getFirst();
        assertThat(youzan.sourceType()).isEqualTo("YOUZAN_SYNC_ISSUE");
        assertThat(youzan.riskType()).isEqualTo("PAYMENT_MISMATCH");
        assertThat(youzan.evidence()).contains("\"eventId\":81");
        RiskSourceRecord reconciliation = sources.getAllValues().getLast();
        assertThat(reconciliation.sourceType()).isEqualTo("ORDER");
        assertThat(reconciliation.riskType()).isEqualTo("ORDER_PAYMENT_CASH");
        assertThat(reconciliation.evidence()).contains("\"expectedFen\":1000", "\"actualFen\":900");
    }
}
