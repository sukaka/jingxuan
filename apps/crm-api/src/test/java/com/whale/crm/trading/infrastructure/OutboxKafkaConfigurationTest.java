package com.whale.crm.trading.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxKafkaConfigurationTest {

    private final OutboxKafkaConfiguration configuration = new OutboxKafkaConfiguration();

    @Test
    void declaresOrderTopicWithReusableSingleBrokerDefaults() {
        NewTopic topic = configuration.orderEventsTopic(1, (short) 1);

        assertThat(topic.name()).isEqualTo("crm.order.events.v1");
        assertThat(topic.numPartitions()).isEqualTo(1);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    void declaresEntitlementTopicWithReusableSingleBrokerDefaults() {
        NewTopic topic = configuration.entitlementEventsTopic(1, (short) 1);

        assertThat(topic.name()).isEqualTo("crm.entitlement.events.v1");
        assertThat(topic.numPartitions()).isEqualTo(1);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    void declaresRefundTopicWithReusableSingleBrokerDefaults() {
        NewTopic topic = configuration.refundEventsTopic(1, (short) 1);

        assertThat(topic.name()).isEqualTo("crm.refund.events.v1");
        assertThat(topic.numPartitions()).isEqualTo(1);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    void declaresSettlementTopicWithReusableSingleBrokerDefaults() {
        NewTopic topic = configuration.settlementEventsTopic(1, (short) 1);

        assertThat(topic.name()).isEqualTo("crm.settlement.events.v1");
        assertThat(topic.numPartitions()).isEqualTo(1);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    void declaresRedemptionTopicWithReusableSingleBrokerDefaults() {
        NewTopic topic = configuration.redemptionEventsTopic(1, (short) 1);

        assertThat(topic.name()).isEqualTo("crm.redemption.events.v1");
        assertThat(topic.numPartitions()).isEqualTo(1);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    void declaresAppointmentTopicWithReusableSingleBrokerDefaults() {
        NewTopic topic = configuration.appointmentEventsTopic(1, (short) 1);

        assertThat(topic.name()).isEqualTo("crm.appointment.events.v1");
        assertThat(topic.numPartitions()).isEqualTo(1);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    void declaresInstitutionTopicWithReusableSingleBrokerDefaults() {
        NewTopic topic = configuration.institutionEventsTopic(1, (short) 1);

        assertThat(topic.name()).isEqualTo("crm.institution.events.v1");
        assertThat(topic.numPartitions()).isEqualTo(1);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    void declaresYouzanTopicWithReusableSingleBrokerDefaults() {
        NewTopic topic = configuration.youzanEventsTopic(1, (short) 1);

        assertThat(topic.name()).isEqualTo("crm.youzan.events.v1");
        assertThat(topic.numPartitions()).isEqualTo(1);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }
}
