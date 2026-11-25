package com.whale.crm.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.fulfillment.api.dto.AppointmentDetail;
import com.whale.crm.fulfillment.api.dto.AppointmentItemRequest;
import com.whale.crm.fulfillment.api.dto.AppointmentOptionsResponse;
import com.whale.crm.fulfillment.api.dto.AppointmentTransitionRequest;
import com.whale.crm.fulfillment.api.dto.CreateAppointmentRequest;
import com.whale.crm.fulfillment.application.AppointmentApplicationService;
import com.whale.crm.fulfillment.application.FulfillmentSupport;
import com.whale.crm.fulfillment.domain.Appointment;
import com.whale.crm.fulfillment.domain.FulfillmentRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.Institution;
import com.whale.crm.trading.application.FulfillmentTradingOperations;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.FulfillmentEntitlementView;
import com.whale.crm.trading.domain.LockedEntitlementBatch;
import com.whale.crm.trading.domain.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppointmentApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    private static final CurrentUser MANAGER_T2 = new CurrentUser(2L, 2L, "mgr2", "管家", "EMPLOYEE", null,
            Set.of("MANAGER"), Set.of("appointment:read", "appointment:write"), Set.of());

    private FulfillmentRepository repository;
    private CustomerScopeSupport scopeSupport;
    private CatalogApplicationService catalogService;
    private FulfillmentTradingOperations tradingOperations;
    private AppointmentApplicationService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        repository = mock(FulfillmentRepository.class);
        scopeSupport = mock(CustomerScopeSupport.class);
        catalogService = mock(CatalogApplicationService.class);
        tradingOperations = mock(FulfillmentTradingOperations.class);
        FulfillmentSupport support = new FulfillmentSupport(repository, scopeSupport,
                mock(InstitutionAccessSupport.class), mock(IdempotencyService.class),
                tradingOperations, new ObjectMapper());
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new AppointmentApplicationService(repository, support, scopeSupport,
                mock(InstitutionAccessSupport.class), catalogService, tradingOperations,
                mock(OutboxService.class), mock(AuditService.class), txManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void optionsAndCreateUseActiveInstitutionsByIdsBeyond100LimitAndPassExplicitTenantId2() {
        Customer customer = new Customer();
        customer.setId(100L);
        customer.setTenantId(2L);
        customer.setOwnerStaffId(2L);
        customer.setTeamId(9L);
        when(scopeSupport.requireVisible(2L, 100L, MANAGER_T2)).thenReturn(customer);

        Entitlement entitlement = new Entitlement(70L, 100L, 20L, 101L, "水光", 2, 2, 0,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "AVAILABLE", NOW, 0L);
        FulfillmentEntitlementView view = new FulfillmentEntitlementView(entitlement, 10L, List.of(150L), "CONFIGURED");
        when(tradingOperations.appointmentOptions(2L, MANAGER_T2, 100L)).thenReturn(List.of(view));
        Institution inst150 = new Institution(150L, "INST-150", "第150家机构", "150院", "310100", "ACTIVE", 0L);
        when(catalogService.activeInstitutionsByIds(2L, MANAGER_T2, Set.of(150L))).thenReturn(List.of(inst150));

        AppointmentOptionsResponse options = service.options(2L, MANAGER_T2, 100L, null);
        assertThat(options.institutions()).extracting(AppointmentOptionsResponse.InstitutionOption::id)
                .containsExactly(150L);
        verify(catalogService, never()).institutions(anyLong(), any(), any(), any(), anyInt(), anyInt());

        Order order = new Order(10L, "J10", 100L, null, 2L, 2L, null, "CRM", null, "REGULAR",
                null, null, "PAID", "PLATFORM", 1000L, 0L, 1000L, 1000L, 0L, NOW, NOW, NOW, 0L);
        when(tradingOperations.requireAppointmentEntitlements(eq(2L), anyList()))
                .thenReturn(new LockedEntitlementBatch(Map.of(10L, order), Map.of(70L, view)));
        Appointment created = new Appointment(30L, "A000000000030", 100L, 150L, 2L, 9L,
                NOW.plusSeconds(3600), "remark", "PENDING_INSTITUTION", 2L, NOW, 0L);
        when(repository.findAppointmentByNo(eq(2L), any())).thenReturn(Optional.of(created));
        when(repository.findAppointment(2L, 30L)).thenReturn(Optional.of(created));

        AppointmentDetail detail = service.create(2L, MANAGER_T2,
                new CreateAppointmentRequest(100L, 150L, NOW.plusSeconds(3600), "remark",
                        List.of(new AppointmentItemRequest(70L, 1))), "appt-t2");
        assertThat(detail.appointment().institutionId()).isEqualTo(150L);
        verify(repository).insertAppointment(eq(2L), any());
        verify(repository).insertAppointmentItem(eq(2L), any());
        verify(repository).insertAppointmentHistory(eq(2L), any());

        when(repository.findAppointmentForUpdate(2L, 30L)).thenReturn(Optional.of(created));
        when(repository.updateAppointmentStatus(2L, 30L, 0L, "PENDING_INSTITUTION", "CANCELED")).thenReturn(1);
        service.transition(2L, MANAGER_T2, 30L, "cancel", new AppointmentTransitionRequest(0L, "cancel"), "cancel-t2");
        verify(repository, org.mockito.Mockito.times(2)).insertAppointmentHistory(eq(2L), any());
    }

    @Test
    void createRejectsInactiveOrMissingInstitutionFromActiveInstitutionsByIds() {
        Customer customer = new Customer();
        customer.setId(100L);
        customer.setTenantId(2L);
        customer.setOwnerStaffId(2L);
        when(scopeSupport.requireVisible(2L, 100L, MANAGER_T2)).thenReturn(customer);
        when(catalogService.activeInstitutionsByIds(2L, MANAGER_T2, Set.of(999L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(2L, MANAGER_T2,
                new CreateAppointmentRequest(100L, 999L, NOW.plusSeconds(3600), "bad",
                        List.of(new AppointmentItemRequest(70L, 1))), "appt-bad"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void appointmentTwelveArgAndFifteenArgConstructorsMapMilestoneTimestamps() {
        Appointment twelveArg = new Appointment(1L, "A001", 10L, 20L, 2L, 3L,
                NOW, "remark", "PENDING_INSTITUTION", 2L, NOW, 0L);
        assertThat(twelveArg.confirmedAt()).isNull();
        assertThat(twelveArg.checkedInAt()).isNull();
        assertThat(twelveArg.serviceDoneAt()).isNull();

        Instant confirmed = NOW.plusSeconds(60);
        Instant checkedIn = NOW.plusSeconds(120);
        Instant serviceDone = NOW.plusSeconds(180);
        Appointment fifteenArg = new Appointment(1L, "A001", 10L, 20L, 2L, 3L,
                NOW, "remark", "SERVICE_DONE", confirmed, checkedIn, serviceDone, 2L, NOW, 3L);
        assertThat(fifteenArg.confirmedAt()).isEqualTo(confirmed);
        assertThat(fifteenArg.checkedInAt()).isEqualTo(checkedIn);
        assertThat(fifteenArg.serviceDoneAt()).isEqualTo(serviceDone);
    }
}
