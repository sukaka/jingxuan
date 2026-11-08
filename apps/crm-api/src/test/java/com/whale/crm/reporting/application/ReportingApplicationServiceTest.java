package com.whale.crm.reporting.application;

import com.whale.crm.customer.application.TaskApplicationService;
import com.whale.crm.customer.domain.TaskDutySummary;
import com.whale.crm.fulfillment.application.AppointmentApplicationService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.refund.application.RefundApplicationService;
import com.whale.crm.reporting.api.dto.OperationsDashboardResponse;
import com.whale.crm.reporting.api.dto.OperationsReportResponse;
import com.whale.crm.reporting.domain.ReportCriteria;
import com.whale.crm.reporting.domain.ReportingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.whale.crm.platform.web.PageResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportingApplicationServiceTest {
    private static final CurrentUser NO_SCOPE_USER = new CurrentUser(90, 1, "none", "无范围", "EMPLOYEE", null,
            Set.of(), Set.of("report:read"), Set.of());
    private static final CurrentUser DUTY_USER = new CurrentUser(91, 1, "duty", "值班用户", "EMPLOYEE", null,
            Set.of(), Set.of("report:read", "customer:read", "task:read", "appointment:read", "refund:read"), Set.of());

    private ReportingRepository repository;
    private DataScopeService dataScopeService;
    private TaskApplicationService taskService;
    private AppointmentApplicationService appointmentService;
    private RefundApplicationService refundService;
    private ReportingApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReportingRepository.class);
        dataScopeService = mock(DataScopeService.class);
        taskService = mock(TaskApplicationService.class);
        appointmentService = mock(AppointmentApplicationService.class);
        refundService = mock(RefundApplicationService.class);
        service = new ReportingApplicationService(repository, dataScopeService, taskService, appointmentService, refundService);
    }

    @Test
    void emptyScopeReturnsZeroFactsWithoutRunningUnboundedSql() {
        when(dataScopeService.scopeFor(NO_SCOPE_USER, DataScopeService.RESOURCE_CUSTOMER))
                .thenReturn(DataScope.none());

        OperationsReportResponse response = service.operations(1, NO_SCOPE_USER, criteria(), "NONE", 20);

        assertThat(response.metrics().paidFen()).isZero();
        assertThat(response.metrics().contactCustomerCount()).isZero();
        assertThat(response.sourceWatermark()).isNull();
        assertThat(response.conversionRates().get("contactToPaid").value()).isNull();
        assertThat(response.conversionRates().get("contactToPaid").status()).isEqualTo("PENDING_DEFINITION");
        verifyNoInteractions(repository);
    }

    @Test
    void emptyScopeDashboardReturnsZeroFactsWithoutRunningUnboundedSql() {
        when(dataScopeService.scopeFor(NO_SCOPE_USER, DataScopeService.RESOURCE_CUSTOMER))
                .thenReturn(DataScope.none());

        OperationsDashboardResponse response = service.operationsDashboard(1, NO_SCOPE_USER, criteria());

        assertThat(response.metrics().paidFen()).isZero();
        assertThat(response.sourceWatermark()).isNull();
        assertThat(response.dailyItems()).isEmpty();
        assertThat(response.institutionRows()).isEmpty();
        assertThat(response.campaignRows()).isEmpty();
        verifyNoInteractions(repository, taskService, appointmentService, refundService);
    }

    @Test
    void dutySummaryUsesScopedCounts() {
        when(dataScopeService.scopeFor(DUTY_USER, DataScopeService.RESOURCE_CUSTOMER)).thenReturn(DataScope.all());
        when(repository.operations(any())).thenReturn(ReportingRepository.OperationsFactRow.empty());
        when(repository.campaignTotals(any())).thenReturn(emptyCampaignFacts());
        when(repository.countNewCustomers(any())).thenReturn(3L);
        when(taskService.dutySummary(eq(1L), eq(DUTY_USER), any(Instant.class), any(Instant.class)))
                .thenReturn(new TaskDutySummary(9, 4, 2));
        when(appointmentService.search(eq(1L), eq(DUTY_USER), isNull(), isNull(),
                eq("PENDING_INSTITUTION"), eq(1), eq(1))).thenReturn(new PageResponse<>(List.of(), 3, 1, 1));
        when(appointmentService.search(eq(1L), eq(DUTY_USER), isNull(), isNull(),
                eq("SERVICE_DONE"), eq(1), eq(1))).thenReturn(new PageResponse<>(List.of(), 2, 1, 1));
        when(refundService.search(eq(1L), eq(DUTY_USER), isNull(), isNull(),
                eq("REQUESTED"), eq(1), eq(1))).thenReturn(new PageResponse<>(List.of(), 1, 1, 1));

        OperationsDashboardResponse response = service.operationsDashboard(1, DUTY_USER, criteria());

        assertThat(response.duty().newCustomerCount()).isEqualTo(3);
        assertThat(response.duty().pendingTaskCount()).isEqualTo(9);
        assertThat(response.duty().dueFollowupCount()).isEqualTo(4);
        assertThat(response.duty().overdueTaskCount()).isEqualTo(2);
        assertThat(response.duty().pendingAppointmentCount()).isEqualTo(3);
        assertThat(response.duty().awaitingRedemptionCount()).isEqualTo(2);
        assertThat(response.duty().pendingRefundCount()).isEqualTo(1);
    }

    @Test
    void dutySummaryHidesCountsWithoutDomainPermissions() {
        when(dataScopeService.scopeFor(NO_SCOPE_USER, DataScopeService.RESOURCE_CUSTOMER)).thenReturn(DataScope.all());
        when(repository.operations(any())).thenReturn(ReportingRepository.OperationsFactRow.empty());
        when(repository.campaignTotals(any())).thenReturn(emptyCampaignFacts());

        OperationsDashboardResponse unpermitted = service.operationsDashboard(1, NO_SCOPE_USER, criteria());

        assertThat(unpermitted.duty().newCustomerCount()).isNull();
        assertThat(unpermitted.duty().pendingTaskCount()).isNull();
        assertThat(unpermitted.duty().dueFollowupCount()).isNull();
        assertThat(unpermitted.duty().overdueTaskCount()).isNull();
        assertThat(unpermitted.duty().pendingAppointmentCount()).isNull();
        assertThat(unpermitted.duty().awaitingRedemptionCount()).isNull();
        assertThat(unpermitted.duty().pendingRefundCount()).isNull();
        verify(repository, never()).countNewCustomers(any());
        verifyNoInteractions(taskService, appointmentService, refundService);
    }

    private ReportingRepository.CampaignFactRow emptyCampaignFacts() {
        return new ReportingRepository.CampaignFactRow(null, null, null, null, null, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }

    private ReportCriteria criteria() {
        return new ReportCriteria(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                "Asia/Shanghai", "ALL", "ALL", "P2_FACT_V1", null, null, null, null, null, null, null);
    }
}
