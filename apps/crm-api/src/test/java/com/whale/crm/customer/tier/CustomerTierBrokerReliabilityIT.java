package com.whale.crm.customer.tier;

import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.support.KafkaTestContainer;
import com.whale.crm.trading.application.OutboxRelayService;
import com.whale.crm.trading.infrastructure.OutboxKafkaConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.customer-tier.kafka.enabled=true", "app.outbox.relay.enabled=false",
        "app.notification.kafka.enabled=false", "app.notification.delivery.worker-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerTierBrokerReliabilityIT extends AbstractMysqlIT {
    private static final GenericContainer<?> KAFKA = KafkaTestContainer.start();

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        KafkaTestContainer.registerProperties(registry, KAFKA);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OutboxRelayService relayService;

    @Test
    void realBrokerTriggersPaymentRefundAndFollowupRecalculationWithInboxDedupe() throws Exception {
        awaitListenerReady();
        activeRule();
        long customerId = customer();
        long orderId = order(customerId);
        jdbcTemplate.update("INSERT INTO crm_payment_txn (tenant_id,order_id,payment_channel,receiver_type,"
                        + "external_trade_no,amount_fen,status,confirmed_at) VALUES (1,?,'BANK','PLATFORM',?,10000,"
                        + "'CONFIRMED','2025-01-01 00:00:00')",
                orderId, "TIERBROKERP" + UUID.randomUUID());
        String eventId = "tier-broker-paid-" + UUID.randomUUID();
        send(OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC, eventId, "order.paid.v1", orderId, customerId);
        awaitSnapshot(eventId);
        assertThat(jdbcTemplate.queryForObject("SELECT tier_code FROM crm_customer WHERE id=?",
                String.class, customerId)).isEqualTo("S");
        assertThat(jdbcTemplate.queryForObject("SELECT source_trigger FROM crm_customer_tier_metric_snapshot "
                + "WHERE recalculation_key=?", String.class, "event:" + eventId)).isEqualTo("ORDER_PAID");

        String refundEvent = "tier-broker-refund-" + UUID.randomUUID();
        send(OutboxKafkaConfiguration.REFUND_EVENTS_TOPIC, refundEvent, "refund.succeeded.v1", 91L, customerId);
        awaitSnapshot(refundEvent);
        AuthCookies admin = login("admin");
        String followupResponse = mockMvc.perform(admin.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", "tier-broker-followup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"channelCode\":\"PHONE\","
                                + "\"contactAt\":\"2025-01-01T00:00:00Z\","
                                + "\"nextFollowupAt\":\"2025-02-01T00:00:00Z\","
                                + "\"summary\":\"Broker合成跟进\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long followupId = objectMapper.readTree(followupResponse).at("/data/id").asLong();
        String followupEvent = "followup.recorded.v1:" + followupId;
        relayUntilSent(followupEvent);
        awaitSnapshot(followupEvent);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 "
                + "AND consumer_group='crm-customer-tier-recalculation' AND event_id IN (?,?,?)",
                Long.class, eventId, refundEvent, followupEvent)).isEqualTo(3);

        send(OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC, eventId, "order.paid.v1", orderId, customerId);
        Thread.sleep(1000);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_metric_snapshot "
                + "WHERE tenant_id=1 AND recalculation_key=?", Long.class, "event:" + eventId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 "
                + "AND consumer_group='crm-customer-tier-recalculation' AND event_id=?",
                Long.class, eventId)).isEqualTo(1);
    }

    @Test
    void sameMillisecondPaidEventUnsupportedTopicEventAndMissingRuleTenantDoNotFailOrMissConsumption()
            throws Exception {
        awaitListenerReady();
        activeRule();

        String unsupportedEventId = "tier-broker-unsupported-" + UUID.randomUUID();
        String unsupportedMsg = """
                {"event_id":"%s","event_type":"order.created.v1","event_version":1,"tenant_id":1,
                "aggregate_type":"order","aggregate_id":"888","occurred_at":"2025-04-01T12:00:00.123Z",
                "correlation_id":"%s","payload":{"orderId":888}}
                """.formatted(unsupportedEventId, unsupportedEventId);
        kafkaTemplate.send(OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC, unsupportedEventId, unsupportedMsg)
                .get(30, TimeUnit.SECONDS);
        awaitInbox(1L, unsupportedEventId);

        long customerId = customer();
        String orderNo = "TIERBSM" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant sameMs = Instant.parse("2025-04-01T12:00:00.123Z");
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "source_system,order_type_code,status,receiver_type,paid_fen,ordered_at,paid_at) "
                        + "VALUES (1,?,?,2,2,'CRM','REGULAR','PAID','PLATFORM',1000000,?,?)",
                orderNo, customerId, Timestamp.from(sameMs), Timestamp.from(sameMs));
        long orderId = jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE tenant_id=1 AND order_no=?",
                Long.class, orderNo);
        jdbcTemplate.update("INSERT INTO crm_payment_txn (tenant_id,order_id,payment_channel,receiver_type,"
                        + "external_trade_no,amount_fen,status,confirmed_at) VALUES (1,?,'BANK','PLATFORM',?,1000000,"
                        + "'CONFIRMED',?)",
                orderId, "TIERBSMP" + UUID.randomUUID(), Timestamp.from(sameMs));

        String sameMsEventId = "tier-broker-same-ms-" + UUID.randomUUID();
        String sameMsMsg = """
                {"event_id":"%s","event_type":"order.paid.v1","event_version":1,"tenant_id":1,
                "aggregate_type":"order","aggregate_id":"%d","occurred_at":"2025-04-01T12:00:00.123Z",
                "correlation_id":"%s","payload":{"customerId":%d}}
                """.formatted(sameMsEventId, orderId, sameMsEventId, customerId);
        kafkaTemplate.send(OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC, sameMsEventId, sameMsMsg)
                .get(30, TimeUnit.SECONDS);
        awaitSnapshot(sameMsEventId);
        assertThat(jdbcTemplate.queryForObject("SELECT actual_consumption_fen FROM crm_customer_tier_metric_snapshot "
                + "WHERE tenant_id=1 AND recalculation_key=?", Long.class, "event:" + sameMsEventId))
                .isEqualTo(1_000_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT tier_code FROM crm_customer WHERE id=?",
                String.class, customerId)).isEqualTo("S");

        String missingRuleEventId = "tier-broker-no-rule-" + UUID.randomUUID();
        String missingRuleMsg = """
                {"event_id":"%s","event_type":"order.paid.v1","event_version":1,"tenant_id":2,
                "aggregate_type":"order","aggregate_id":"999","occurred_at":"2025-04-01T12:00:00.123Z",
                "correlation_id":"%s","payload":{"customerId":999}}
                """.formatted(missingRuleEventId, missingRuleEventId);
        kafkaTemplate.send(OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC, missingRuleEventId, missingRuleMsg)
                .get(30, TimeUnit.SECONDS);
        awaitInbox(2L, missingRuleEventId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_metric_snapshot "
                + "WHERE tenant_id=2 AND recalculation_key=?", Long.class, "event:" + missingRuleEventId)).isZero();
    }

    private void awaitInbox(long tenantId, String eventId) throws InterruptedException {
        long count = 0;
        for (int attempt = 0; attempt < 900; attempt++) {
            count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_inbox_event "
                    + "WHERE tenant_id=? AND consumer_group='crm-customer-tier-recalculation' AND event_id=?",
                    Long.class, tenantId, eventId);
            if (count == 1) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(count).isEqualTo(1);
    }

    private void relayUntilSent(String eventId) throws Exception {
        for (int attempt = 0; attempt < 1200; attempt++) {
            relayService.relayOnce();
            String status = jdbcTemplate.queryForObject("SELECT status FROM crm_outbox_event "
                    + "WHERE tenant_id=1 AND event_id=?", String.class, eventId);
            if ("SENT".equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
        var outbox = jdbcTemplate.queryForList("SELECT event_id,status,retry_count,sent_at,last_error "
                + "FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?", eventId);
        throw new AssertionError("目标 Outbox 事件未发布: " + outbox);
    }

    private void send(String topic, String eventId, String eventType, long aggregateId, long customerId)
            throws Exception {
        String message = """
                {"event_id":"%s","event_type":"%s","event_version":1,"tenant_id":1,
                "aggregate_type":"customer","aggregate_id":"%d","occurred_at":"2025-03-01T00:00:00Z",
                "correlation_id":"%s","payload":{"customerId":%d}}
                """.formatted(eventId, eventType, aggregateId, eventId, customerId);
        kafkaTemplate.send(topic, eventId, message).get(30, TimeUnit.SECONDS);
    }

    private void awaitListenerReady() throws InterruptedException {
        for (int attempt = 0; attempt < 1200; attempt++) {
            boolean ready = listenerRegistry.getListenerContainers().stream()
                    .allMatch(container -> container.isRunning() && !container.getAssignedPartitions().isEmpty());
            if (ready) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("客户分级 Kafka Listener 分区分配未完成");
    }

    private void awaitSnapshot(String eventId) throws InterruptedException {
        long count = 0;
        for (int attempt = 0; attempt < 900; attempt++) {
            count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_metric_snapshot "
                    + "WHERE tenant_id=1 AND recalculation_key=?", Long.class, "event:" + eventId);
            if (count == 1) {
                return;
            }
            Thread.sleep(100);
        }
        var outbox = jdbcTemplate.queryForList("SELECT event_id,status,retry_count,sent_at,last_error "
                + "FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?", eventId);
        var inbox = jdbcTemplate.queryForList("SELECT consumer_group,event_id,processed_at FROM crm_inbox_event "
                + "WHERE tenant_id=1 AND event_id=?", eventId);
        assertThat(count).as("等待事件快照失败：outbox=%s，inbox=%s", outbox, inbox).isEqualTo(1);
    }

    private void activeRule() {
        jdbcTemplate.update("UPDATE crm_customer_tier_rule SET status='RETIRED',retired_at=CURRENT_TIMESTAMP(3),"
                + "version=version+1 WHERE tenant_id=1 AND status='ACTIVE'");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update("INSERT INTO crm_customer_tier_rule (tenant_id,rule_code,version_no,rule_name,zone_id,"
                        + "status,metric_config_json,created_by,activated_at) VALUES (1,?,1,'Broker分级测试','UTC',"
                        + "'ACTIVE','[]',1,CURRENT_TIMESTAMP(3))", "TIER_BROKER_" + suffix);
        long ruleId = jdbcTemplate.queryForObject("SELECT id FROM crm_customer_tier_rule WHERE tenant_id=1 "
                + "AND rule_code=?", Long.class, "TIER_BROKER_" + suffix);
        insertLevel(ruleId, "S", 1, 90, 1_000_000L);
        insertLevel(ruleId, "A", 2, 70, 800_001L);
        insertLevel(ruleId, "B", 3, 50, 1000L);
        insertLevel(ruleId, "C", 4, 30, 1L);
        insertLevel(ruleId, "D", 5, 0, 0L);
    }

    private void insertLevel(long ruleId, String code, int sort, int minScore, long minConsumptionFen) {
        jdbcTemplate.update("INSERT INTO crm_customer_tier_level (tenant_id,rule_id,tier_code,tier_name,sort_no,"
                + "min_score,min_consumption_fen) VALUES (1,?,?,?,?,?,?)", ruleId, code, code + "级", sort,
                minScore, minConsumptionFen);
    }

    private long customer() {
        String number = "TIERBC" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,nickname,tier_code) "
                + "VALUES (1,2,1,?,'Broker客户','D')", number);
        return jdbcTemplate.queryForObject("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                Long.class, number);
    }

    private long order(long customerId) {
        String number = "TIERBO" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "source_system,order_type_code,status,receiver_type,paid_fen,ordered_at,paid_at) "
                        + "VALUES (1,?,?,2,2,'CRM','REGULAR','PAID','PLATFORM',1000000,?,?)",
                number, customerId, Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
        return jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE tenant_id=1 AND order_no=?",
                Long.class, number);
    }
}
