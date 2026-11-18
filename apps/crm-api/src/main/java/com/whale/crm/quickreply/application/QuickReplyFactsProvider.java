package com.whale.crm.quickreply.application;

import com.whale.crm.customer.api.dto.CustomerDetailResponse;
import com.whale.crm.customer.application.CustomerApplicationService;
import com.whale.crm.fulfillment.application.AppointmentApplicationService;
import com.whale.crm.fulfillment.application.RedemptionApplicationService;
import com.whale.crm.fulfillment.domain.Appointment;
import com.whale.crm.fulfillment.domain.Redemption;
import com.whale.crm.institution.api.dto.InstitutionProfileResponse;
import com.whale.crm.institution.application.InstitutionProfileService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.trading.application.EntitlementApplicationService;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class QuickReplyFactsProvider {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final CustomerApplicationService customerService;
    private final EntitlementApplicationService entitlementService;
    private final OrderApplicationService orderService;
    private final AppointmentApplicationService appointmentService;
    private final RedemptionApplicationService redemptionService;
    private final InstitutionProfileService institutionService;

    public QuickReplyFactsProvider(CustomerApplicationService customerService,
                                   EntitlementApplicationService entitlementService,
                                   OrderApplicationService orderService,
                                   AppointmentApplicationService appointmentService,
                                   RedemptionApplicationService redemptionService,
                                   InstitutionProfileService institutionService) {
        this.customerService = customerService;
        this.entitlementService = entitlementService;
        this.orderService = orderService;
        this.appointmentService = appointmentService;
        this.redemptionService = redemptionService;
        this.institutionService = institutionService;
    }

    public Map<String, Object> load(long tenantId, CurrentUser user, long customerId) {
        Map<String, Object> facts = new HashMap<>();
        CustomerDetailResponse customer = customerService.detail(tenantId, user, customerId);
        put(facts, "customer_name", customer.displayName() != null && !customer.displayName().isBlank()
                ? customer.displayName() : customer.nickname() != null && !customer.nickname().isBlank()
                ? customer.nickname() : customer.customerNo());
        put(facts, "manager_name", customer.ownerName());
        loadEntitlements(tenantId, user, customerId, facts);
        loadOrder(tenantId, user, customerId, facts);
        loadAppointments(tenantId, user, customerId, facts);
        loadRedemption(tenantId, user, customerId, facts);
        return facts;
    }

    private void loadEntitlements(long tenantId, CurrentUser user, long customerId, Map<String, Object> facts) {
        if (!user.permissions().contains("entitlement:read")) {
            return;
        }
        List<Entitlement> items = entitlementService.search(tenantId, user, customerId, null,
                null, true, 1, 100).items();
        items.stream().max(Comparator.comparing(Entitlement::createdAt,
                Comparator.nullsFirst(Comparator.naturalOrder()))).ifPresent(item -> {
            put(facts, "project_name", item.projectName());
            put(facts, "total_count", item.grantedCount());
            put(facts, "used_count", Math.max(0, item.grantedCount() - item.availableCount()));
            put(facts, "remaining_count", item.availableCount());
            put(facts, "expire_date", item.validTo());
        });
    }

    private void loadOrder(long tenantId, CurrentUser user, long customerId, Map<String, Object> facts) {
        if (!user.permissions().contains("order:read")) {
            return;
        }
        List<Order> items = orderService.search(tenantId, user, customerId, null, 1, 1).items();
        if (!items.isEmpty()) {
            put(facts, "order_no", items.getFirst().orderNo());
        }
    }

    private void loadAppointments(long tenantId, CurrentUser user, long customerId, Map<String, Object> facts) {
        if (!user.permissions().contains("appointment:read")) {
            return;
        }
        List<Appointment> items = appointmentService.search(tenantId, user, customerId, null,
                null, 1, 100).items();
        Instant now = Instant.now();
        items.stream().filter(item -> "CONFIRMED".equals(item.status()) && item.scheduledAt() != null
                        && !item.scheduledAt().isBefore(now))
                .min(Comparator.comparing(Appointment::scheduledAt)).ifPresent(next -> {
            put(facts, "appointment_date", LocalDate.ofInstant(next.scheduledAt(), ZONE));
            put(facts, "appointment_time", TIME.format(next.scheduledAt().atZone(ZONE)));
            put(facts, "institution_name", institutionValue(tenantId, user, next.institutionId(), true));
            put(facts, "institution_address", institutionValue(tenantId, user, next.institutionId(), false));
        });
        items.stream().filter(item -> List.of("CHECKED_IN", "SERVICE_DONE", "REDEEMED").contains(item.status())
                        && item.scheduledAt() != null)
                .max(Comparator.comparing(Appointment::scheduledAt)).ifPresent(last ->
                        put(facts, "last_visit_date", LocalDate.ofInstant(last.scheduledAt(), ZONE)));
    }

    private void loadRedemption(long tenantId, CurrentUser user, long customerId, Map<String, Object> facts) {
        if (!user.permissions().contains("redemption:read")) {
            return;
        }
        redemptionService.search(tenantId, user, customerId, null, null, "POSTED", 1, 100)
                .items().stream().filter(item -> item.postedAt() != null)
                .max(Comparator.comparing(Redemption::postedAt)).ifPresent(last ->
                        put(facts, "last_writeoff_date", LocalDate.ofInstant(last.postedAt(), ZONE)));
    }

    private String institutionValue(long tenantId, CurrentUser user, Long institutionId, boolean name) {
        if (institutionId == null || !user.permissions().contains("institution:read")) {
            return null;
        }
        try {
            InstitutionProfileResponse profile = institutionService.detail(tenantId, user, institutionId);
            return name ? (profile.shortName() != null && !profile.shortName().isBlank()
                    ? profile.shortName() : profile.fullName()) : profile.address();
        } catch (BusinessException ex) {
            return null;
        }
    }

    private void put(Map<String, Object> facts, String code, Object value) {
        if (value != null && !value.toString().isBlank()) {
            facts.put(code, value);
        }
    }
}
