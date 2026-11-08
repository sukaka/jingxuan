package com.whale.crm.trading.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
public class OutboxKafkaConfiguration {
    public static final String ORDER_EVENTS_TOPIC = "crm.order.events.v1";
    public static final String ENTITLEMENT_EVENTS_TOPIC = "crm.entitlement.events.v1";
    public static final String REFUND_EVENTS_TOPIC = "crm.refund.events.v1";
    public static final String SETTLEMENT_EVENTS_TOPIC = "crm.settlement.events.v1";
    public static final String REDEMPTION_EVENTS_TOPIC = "crm.redemption.events.v1";
    public static final String APPOINTMENT_EVENTS_TOPIC = "crm.appointment.events.v1";
    public static final String INSTITUTION_EVENTS_TOPIC = "crm.institution.events.v1";
    public static final String YOUZAN_EVENTS_TOPIC = "crm.youzan.events.v1";
    public static final String WECOM_EVENTS_TOPIC = "crm.wecom.events.v1";
    public static final String KNOWLEDGE_SYNC_TOPIC = "crm.knowledge.sync.v1";

    @Bean
    public NewTopic orderEventsTopic(
            @Value("${app.outbox.kafka.partitions:1}") int partitions,
            @Value("${app.outbox.kafka.replication-factor:1}") short replicationFactor) {
        return TopicBuilder.name(ORDER_EVENTS_TOPIC)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic entitlementEventsTopic(
            @Value("${app.outbox.kafka.partitions:1}") int partitions,
            @Value("${app.outbox.kafka.replication-factor:1}") short replicationFactor) {
        return TopicBuilder.name(ENTITLEMENT_EVENTS_TOPIC)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic refundEventsTopic(
            @Value("${app.outbox.kafka.partitions:1}") int partitions,
            @Value("${app.outbox.kafka.replication-factor:1}") short replicationFactor) {
        return TopicBuilder.name(REFUND_EVENTS_TOPIC)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic settlementEventsTopic(
            @Value("${app.outbox.kafka.partitions:1}") int partitions,
            @Value("${app.outbox.kafka.replication-factor:1}") short replicationFactor) {
        return TopicBuilder.name(SETTLEMENT_EVENTS_TOPIC)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic redemptionEventsTopic(
            @Value("${app.outbox.kafka.partitions:1}") int partitions,
            @Value("${app.outbox.kafka.replication-factor:1}") short replicationFactor) {
        return TopicBuilder.name(REDEMPTION_EVENTS_TOPIC)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic appointmentEventsTopic(
            @Value("${app.outbox.kafka.partitions:1}") int partitions,
            @Value("${app.outbox.kafka.replication-factor:1}") short replicationFactor) {
        return TopicBuilder.name(APPOINTMENT_EVENTS_TOPIC)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic institutionEventsTopic(
            @Value("${app.outbox.kafka.partitions:1}") int partitions,
            @Value("${app.outbox.kafka.replication-factor:1}") short replicationFactor) {
        return TopicBuilder.name(INSTITUTION_EVENTS_TOPIC)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic youzanEventsTopic(
            @Value("${app.outbox.kafka.partitions:1}") int partitions,
            @Value("${app.outbox.kafka.replication-factor:1}") short replicationFactor) {
        return TopicBuilder.name(YOUZAN_EVENTS_TOPIC)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic wecomEventsTopic(
            @Value("${app.outbox.kafka.partitions:1}") int partitions,
            @Value("${app.outbox.kafka.replication-factor:1}") short replicationFactor) {
        return TopicBuilder.name(WECOM_EVENTS_TOPIC)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic knowledgeSyncTopic(
            @Value("${app.outbox.kafka.partitions:1}") int partitions,
            @Value("${app.outbox.kafka.replication-factor:1}") short replicationFactor) {
        return TopicBuilder.name(KNOWLEDGE_SYNC_TOPIC)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }
}
