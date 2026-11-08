package com.whale.crm.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.risk.api.dto.ClaimRiskCaseRequest;
import com.whale.crm.risk.api.dto.CloseRiskCaseRequest;
import com.whale.crm.risk.api.dto.ResolveRiskCaseRequest;
import com.whale.crm.risk.application.RiskAccessService;
import com.whale.crm.risk.application.RiskCaseApplicationService;
import com.whale.crm.risk.application.RiskEvidenceMasker;
import com.whale.crm.risk.application.RiskSourceSynchronizer;
import com.whale.crm.risk.domain.RiskCase;
import com.whale.crm.risk.domain.RiskCaseHistory;
import com.whale.crm.risk.domain.RiskCaseRepository;
import com.whale.crm.risk.domain.RiskCustomerLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskCaseApplicationServiceTest {
    private static final CurrentUser ADMIN = new CurrentUser(1, 1, "admin", "管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("risk:read", "risk:write"), Set.of());

    @Mock RiskCaseRepository repository;
    @Mock RiskSourceSynchronizer sourceSynchronizer;
    @Mock AuditService auditService;

    private RiskCaseApplicationService service;

    @BeforeEach
    void setUp() {
        service = new RiskCaseApplicationService(repository, new RiskAccessService(), sourceSynchronizer,
                auditService, new RiskEvidenceMasker(new ObjectMapper().findAndRegisterModules()));
    }

    @Test
    void claimTransitionsOpenAndAppendsHistoryAndAudit() {
        RiskCase open = riskCase("OPEN", null, null, 0);
        RiskCase assigned = riskCase("ASSIGNED", 1L, null, 1);
        when(repository.findByIdForUpdate(1, 10)).thenReturn(Optional.of(open));
        when(repository.updateStatus(eq(1L), eq(10L), eq(0L), eq("OPEN"), eq("ASSIGNED"), eq(1L),
                eq(null), eq(null))).thenReturn(1);
        when(repository.findById(1, 10)).thenReturn(Optional.of(assigned));
        when(repository.findHistory(1, 10)).thenReturn(List.of());

        var response = service.claim(ADMIN, 10, new ClaimRiskCaseRequest(0L, "开始核查"));

        assertThat(response.riskCase().status()).isEqualTo("ASSIGNED");
        assertThat(response.riskCase().ownerUserId()).isEqualTo(1);
        ArgumentCaptor<RiskCaseHistory> history = ArgumentCaptor.forClass(RiskCaseHistory.class);
        verify(repository).insertHistory(history.capture());
        assertThat(history.getValue().action()).isEqualTo("CLAIM");
        assertThat(history.getValue().fromStatus()).isEqualTo("OPEN");
        assertThat(history.getValue().toStatus()).isEqualTo("ASSIGNED");
        assertThat(history.getValue().actorUserId()).isEqualTo(1);
        verify(auditService).record(eq(1L), eq(1L), eq("RISK_CASE_CLAIM"), eq("RISK_CASE"), eq("10"),
                any(), any(), eq("开始核查"));
    }

    @Test
    void resolveRequiresAssignedAndCloseRequiresResolved() {
        when(repository.findByIdForUpdate(1, 10)).thenReturn(Optional.of(riskCase("OPEN", null, null, 0)));

        assertThatThrownBy(() -> service.resolve(ADMIN, 10, new ResolveRiskCaseRequest(0L, "已核实")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(repository, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                any(), any(), any());
        verify(repository, never()).insertHistory(any());
        verifyNoInteractions(auditService);

        org.mockito.Mockito.reset(repository);
        when(repository.findByIdForUpdate(1, 10)).thenReturn(Optional.of(riskCase("ASSIGNED", 1L, null, 1)));
        assertThatThrownBy(() -> service.close(ADMIN, 10, new CloseRiskCaseRequest(1L, "复核完成")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("RESOLVED");
    }

    @Test
    void staleVersionCannotChangeStateOrAppendHistory() {
        when(repository.findByIdForUpdate(1, 10))
                .thenReturn(Optional.of(riskCase("ASSIGNED", 1L, null, 2)));

        assertThatThrownBy(() -> service.resolve(ADMIN, 10, new ResolveRiskCaseRequest(1L, "已核实")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT);

        verify(repository, never()).updateStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                any(), any(), any());
        verify(repository, never()).insertHistory(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void institutionAndOrdinaryManagerCannotReadGlobalLedgerEvenWithPermission() {
        CurrentUser institution = new CurrentUser(8, 1, "inst", "机构", "INSTITUTION", null,
                Set.of("INSTITUTION_STAFF"), Set.of("risk:read", "risk:write"), Set.of(1L));
        CurrentUser manager = new CurrentUser(2, 1, "manager", "管家", "EMPLOYEE", 1L,
                Set.of("MANAGER"), Set.of("risk:read", "risk:write"), Set.of());

        for (CurrentUser user : List.of(institution, manager)) {
            assertThatThrownBy(() -> service.get(user, 10))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThatThrownBy(() -> service.claim(user, 10, new ClaimRiskCaseRequest(0L, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        }
        verifyNoInteractions(repository, sourceSynchronizer, auditService);
    }

    @Test
    void revokedReadPermissionIsAlsoEnforcedInsideApplicationService() {
        CurrentUser ownerWithoutRisk = new CurrentUser(7, 1, "owner", "经营负责人", "EMPLOYEE", null,
                Set.of("OWNER"), Set.of("order:read"), Set.of());

        assertThatThrownBy(() -> service.get(ownerWithoutRisk, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(repository, sourceSynchronizer, auditService);
    }

    @Test
    void searchAddsRealCustomerAndOrderFromReadOnlySourceLink() {
        RiskCase item = riskCase("OPEN", null, null, 0);
        when(repository.search(any())).thenReturn(List.of(item));
        when(repository.countSearch(any())).thenReturn(1L);
        when(repository.findCustomerLinks(1, List.of(10L))).thenReturn(List.of(
                new RiskCustomerLink(10L, 21L, "J000000000021", 31L, "C0000000031", "合成真实客户")));

        var result = service.search(ADMIN, null, null, null, null, null, null, null, 1, 20);

        assertThat(result.items()).hasSize(1);
        var view = result.items().getFirst();
        assertThat(view.orderId()).isEqualTo(21L);
        assertThat(view.orderNo()).isEqualTo("J000000000021");
        assertThat(view.customerId()).isEqualTo(31L);
        assertThat(view.customerNo()).isEqualTo("C0000000031");
        assertThat(view.customerName()).isEqualTo("合成真实客户");
        verify(repository).findCustomerLinks(1, List.of(10L));
    }

    @Test
    void missingSourceLinkStaysNullInsteadOfFabricatingCustomer() {
        RiskCase item = riskCase("OPEN", null, null, 0);
        when(repository.findById(1, 10)).thenReturn(Optional.of(item));
        when(repository.findCustomerLinks(1, List.of(10L))).thenReturn(List.of());
        when(repository.findHistory(1, 10)).thenReturn(List.of());

        var result = service.get(ADMIN, 10);

        assertThat(result.riskCase().orderId()).isNull();
        assertThat(result.riskCase().orderNo()).isNull();
        assertThat(result.riskCase().customerId()).isNull();
        assertThat(result.riskCase().customerNo()).isNull();
        assertThat(result.riskCase().customerName()).isNull();
    }

    private static RiskCase riskCase(String status, Long owner, String resolution, long version) {
        Instant now = Instant.parse("2026-10-26T08:00:00Z");
        return new RiskCase(10L, 1, "RC-TEST", "PAYMENT_MISMATCH", "HIGH", "YOUZAN_SYNC_ISSUE",
                "99", status, owner, "支付金额不一致", resolution,
                resolution == null ? null : now, now, now, version);
    }
}
