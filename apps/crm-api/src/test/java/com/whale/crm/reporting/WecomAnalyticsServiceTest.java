package com.whale.crm.reporting;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.reporting.application.WecomAnalyticsService;
import com.whale.crm.reporting.domain.WecomAnalyticsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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

class WecomAnalyticsServiceTest {
    private WecomAnalyticsRepository repository;
    private DataScopeService scopes;
    private WecomAnalyticsService service;
    private CurrentUser user;

    @BeforeEach
    void setUp() {
        repository = mock(WecomAnalyticsRepository.class);
        scopes = mock(DataScopeService.class);
        service = new WecomAnalyticsService(repository, scopes);
        user = new CurrentUser(2L, 1L, "manager1", "测试管家", "EMPLOYEE", 1L,
                Set.of("MANAGER"), Set.of("report:read"), Set.of());
    }

    @Test
    void ratesExposeTheirRealNumeratorsAndDenominators() {
        when(scopes.scopeFor(user, DataScopeService.RESOURCE_CUSTOMER)).thenReturn(DataScope.all());
        when(repository.quickReplyFacts(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anySet(), anyLong(), any()))
                .thenReturn(new WecomAnalyticsRepository.QuickReplyFacts(10, 4, 3, 2, 1, 2, 2));
        when(repository.aiSuggestionFacts(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anySet(), anyLong(), any()))
                .thenReturn(new WecomAnalyticsRepository.AiSuggestionFacts(8, 3, 2, 3, 1, 2));
        when(repository.aiUsageFacts(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anySet(), anyLong(), any()))
                .thenReturn(new WecomAnalyticsRepository.AiUsageFacts(2, 1, 1, 0));
        when(repository.appointmentFacts(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anySet(), anyLong(), any()))
                .thenReturn(new WecomAnalyticsRepository.AppointmentFacts(5, 2));
        when(repository.paymentFacts(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anySet(), anyLong(), any()))
                .thenReturn(new WecomAnalyticsRepository.PaymentFacts(10, 4, 1));

        var result = service.analytics(user, LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-11"), null);

        assertThat(result.quickReply().sdkSuccessRate()).isEqualByComparingTo("50.00");
        assertThat(result.aiReply().cohortAcceptanceRate()).isEqualByComparingTo("37.50");
        assertThat(result.conversion().appointmentArrivalRate()).isEqualByComparingTo("40.00");
        assertThat(result.conversion().repeatCustomerRate()).isEqualByComparingTo("25.00");
        assertThat(result.metricVersion()).isEqualTo("P3_FACT_V1");
        assertThat(result.unsupportedMetrics()).contains("H5_OPEN_RATE", "CHAT_CAUSAL_ATTRIBUTION");
    }

    @Test
    void emptyScopeDoesNotQueryOrTurnMissingDenominatorsIntoZeroRates() {
        when(scopes.scopeFor(user, DataScopeService.RESOURCE_CUSTOMER)).thenReturn(DataScope.none());

        var result = service.analytics(user, null, null, null);

        assertThat(result.quickReply().usageCount()).isZero();
        assertThat(result.aiReply().cohortAcceptanceRate()).isNull();
        assertThat(result.conversion().appointmentArrivalRate()).isNull();
        assertThat(result.scopeType()).isEqualTo("NONE");
        verify(repository, never()).quickReplyFacts(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anySet(), anyLong(), any());
    }

    @Test
    void selfScopeCannotQueryAnotherEmployee() {
        when(scopes.scopeFor(user, DataScopeService.RESOURCE_CUSTOMER)).thenReturn(DataScope.self());

        assertThatThrownBy(() -> service.analytics(user, null, null, 3L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository, never()).paymentFacts(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anySet(), anyLong(), any());
    }

    @Test
    void institutionAndMissingPermissionAreRejected() {
        CurrentUser institution = new CurrentUser(8L, 1L, "inst1", "机构测试", "INSTITUTION", null,
                Set.of("INSTITUTION_OPERATOR"), Set.of("report:read"), Set.of(1L));
        when(scopes.scopeFor(institution, DataScopeService.RESOURCE_CUSTOMER)).thenReturn(DataScope.institution(Set.of(1L)));
        assertThatThrownBy(() -> service.analytics(institution, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        CurrentUser noPermission = new CurrentUser(2L, 1L, "manager1", "测试管家", "EMPLOYEE", 1L,
                Set.of("MANAGER"), Set.of(), Set.of());
        assertThatThrownBy(() -> service.analytics(noPermission, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository, never()).appointmentFacts(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anySet(), anyLong(), any());
    }
}
