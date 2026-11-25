package com.whale.crm.fulfillment;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FulfillmentMapperContractTest {
    private static final String NAMESPACE = "com.whale.crm.fulfillment.infrastructure.mapper.FulfillmentMapper.";
    private Configuration configuration;

    @BeforeEach
    void parseMapper() throws Exception {
        configuration = new Configuration();
        String resource = "mapper/fulfillment/FulfillmentMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            XMLMapperBuilder builder = new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments());
            builder.parse();
        }
    }

    @Test
    void updateAppointmentStatusMaintainsMilestoneTimestampsAndGuardsServiceDone() {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", 1L);
        params.put("id", 10L);
        params.put("expectedVersion", 2L);
        params.put("expectedStatus", "SERVICE_DONE");
        params.put("toStatus", "SERVICE_DONE");

        String sql = configuration.getMappedStatement(NAMESPACE + "updateAppointmentStatus")
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();

        assertThat(sql).contains(
                "confirmed_at=CASE WHEN ? = 'CONFIRMED' THEN COALESCE(confirmed_at, UTC_TIMESTAMP(3)) ELSE confirmed_at END",
                "checked_in_at=CASE WHEN ? = 'CHECKED_IN' THEN COALESCE(checked_in_at, UTC_TIMESTAMP(3)) ELSE checked_in_at END",
                "service_done_at=CASE WHEN ? = 'CHECKED_IN' AND ? = 'SERVICE_DONE' THEN COALESCE(service_done_at, UTC_TIMESTAMP(3)) ELSE service_done_at END");
    }
}
