package com.whale.crm.customer.tier.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.tier.domain.CustomerTierRepository;
import com.whale.crm.customer.tier.domain.CustomerTierRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerTierEventConsumerTest {
    @Mock CustomerTierRecalculationService recalculationService;
    @Mock CustomerTierRepository tierRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock PlatformTransactionManager transactionManager;

    private CustomerTierEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new CustomerTierEventConsumer(recalculationService, tierRepository, jdbcTemplate,
                new ObjectMapper().findAndRegisterModules(), transactionManager);
    }

    @Test
    void sameMillisecondTriggerEventUsesPlusOneMillisecondAsOf() {
        Instant occurredAt = Instant.parse("2025-03-01T00:00:00.123Z");
        when(tierRepository.findActiveRule(1L)).thenReturn(Optional.of(activeRule(1L)));

        String message = """
                {"event_id":"evt-same-ms","event_type":"order.paid.v1","event_version":1,"tenant_id":1,
                "occurred_at":"2025-03-01T00:00:00.123Z","payload":{"customerId":42}}
                """;
        consumer.consume(message);

        verify(jdbcTemplate).update(anyString(), eq(1L), eq(CustomerTierEventConsumer.GROUP), eq("evt-same-ms"));
        verify(recalculationService).recalculate(1L, 42L, occurredAt.plusMillis(1),
                "ORDER_PAID", "event:evt-same-ms", null);
    }

    @Test
    void unsupportedSharedTopicEventIsRecordedInInboxWithoutCustomerIdOrRecalculation() {
        String withoutCustomer = """
                {"event_id":"evt-order-created","event_type":"order.created.v1","event_version":1,"tenant_id":1,
                "occurred_at":"2025-03-01T00:00:00Z","payload":{"orderId":99}}
                """;
        consumer.consume(withoutCustomer);

        verify(jdbcTemplate).update(anyString(), eq(1L), eq(CustomerTierEventConsumer.GROUP), eq("evt-order-created"));
        verifyNoInteractions(tierRepository, recalculationService);
    }

    @Test
    void missingActiveRuleRecordsInboxCleanlyWithoutThrowingOrRecalculating() {
        when(tierRepository.findActiveRule(2L)).thenReturn(Optional.empty());

        String message = """
                {"event_id":"evt-no-rule","event_type":"payment.confirmed.v1","event_version":1,"tenant_id":2,
                "occurred_at":"2025-03-01T00:00:00Z","payload":{"customerId":77}}
                """;
        consumer.consume(message);

        verify(jdbcTemplate).update(anyString(), eq(2L), eq(CustomerTierEventConsumer.GROUP), eq("evt-no-rule"));
        verify(tierRepository).findActiveRule(2L);
        verifyNoInteractions(recalculationService);
    }

    private CustomerTierRule activeRule(long tenantId) {
        return new CustomerTierRule(10L, tenantId, "RULE_1", 1, "默认规则", "UTC", "ACTIVE", "[]",
                1L, Instant.now(), null, Instant.now(), Instant.now(), 0L);
    }
}
