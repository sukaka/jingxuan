package com.whale.crm.reporting;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WecomAnalyticsApiIT extends AbstractMysqlIT {
    private static final String FROM = "2026-11-10";
    private static final String TO = "2026-11-11";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void analyticsUsesActualLogsAndScopedBusinessFacts() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long firstCustomer = customer(suffix + "-a", 2, 1);
        long secondCustomer = customer(suffix + "-b", 3, 2);
        long templateId = jdbcTemplate.queryForObject("SELECT id FROM crm_quick_reply_template "
                + "WHERE tenant_id=1 AND code='PROJECT_REMAINING'", Long.class);
        long accepted = suggestion(suffix, "accepted", firstCustomer, 2, "ACCEPTED", true, true);
        suggestion(suffix, "rejected", firstCustomer, 2, "REJECTED", false, false);
        suggestion(suffix, "pending", secondCustomer, 3, "PROPOSED", false, false);
        usage(suffix, "plain-copy", templateId, firstCustomer, null, "COPIED", false);
        usage(suffix, "ai-copy", templateId, firstCustomer, accepted, "COPIED", true);
        usage(suffix, "ai-success", templateId, firstCustomer, accepted, "SDK_SUCCESS", false);
        usage(suffix, "ai-unknown", templateId, firstCustomer, accepted, "SDK_UNKNOWN", false);

        appointment(suffix, "arrived", firstCustomer, true);
        appointment(suffix, "no-show", firstCustomer, false);
        appointment(suffix, "other-team", secondCustomer, true);
        paidOrder(suffix, "first-a", firstCustomer, 2, 1);
        paidOrder(suffix, "second-a", firstCustomer, 2, 1);
        paidOrder(suffix, "third-a", firstCustomer, 2, 2);
        paidOrder(suffix, "first-b", secondCustomer, 3, 1);
        pendingOrder(suffix, firstCustomer);

        mockMvc.perform(get("/api/v1/reports/wecom-analytics").param("from", FROM).param("to", TO)
                        .cookie(login("admin").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metricVersion").value("P3_FACT_V1"))
                .andExpect(jsonPath("$.data.scopeType").value("ALL"))
                .andExpect(jsonPath("$.data.fromUtc").value("2026-11-09T16:00:00Z"))
                .andExpect(jsonPath("$.data.toUtcExclusive").value("2026-11-11T16:00:00Z"))
                .andExpect(jsonPath("$.data.quickReply.usageCount").value(4))
                .andExpect(jsonPath("$.data.quickReply.copiedCount").value(2))
                .andExpect(jsonPath("$.data.quickReply.sdkSuccessCount").value(1))
                .andExpect(jsonPath("$.data.quickReply.sdkUnknownCount").value(1))
                .andExpect(jsonPath("$.data.quickReply.editedCount").value(1))
                .andExpect(jsonPath("$.data.quickReply.aiLinkedUsageCount").value(3))
                .andExpect(jsonPath("$.data.quickReply.sdkSuccessRate").value(50.00))
                .andExpect(jsonPath("$.data.aiReply.generatedCount").value(3))
                .andExpect(jsonPath("$.data.aiReply.acceptedCount").value(1))
                .andExpect(jsonPath("$.data.aiReply.rejectedCount").value(1))
                .andExpect(jsonPath("$.data.aiReply.pendingCount").value(1))
                .andExpect(jsonPath("$.data.aiReply.editedAcceptedCount").value(1))
                .andExpect(jsonPath("$.data.aiReply.mockGeneratedCount").value(3))
                .andExpect(jsonPath("$.data.aiReply.copiedCount").value(1))
                .andExpect(jsonPath("$.data.aiReply.sdkSuccessCount").value(1))
                .andExpect(jsonPath("$.data.aiReply.sdkUnknownCount").value(1))
                .andExpect(jsonPath("$.data.aiReply.cohortAcceptanceRate").value(33.33))
                .andExpect(jsonPath("$.data.conversion.confirmedAppointmentCount").value(3))
                .andExpect(jsonPath("$.data.conversion.checkedInFromConfirmedCount").value(2))
                .andExpect(jsonPath("$.data.conversion.appointmentArrivalRate").value(66.67))
                .andExpect(jsonPath("$.data.conversion.paidOrderCount").value(4))
                .andExpect(jsonPath("$.data.conversion.paidCustomerCount").value(2))
                .andExpect(jsonPath("$.data.conversion.repeatPaidCustomerCount").value(1))
                .andExpect(jsonPath("$.data.conversion.repeatCustomerRate").value(50.00))
                .andExpect(jsonPath("$.data.unsupportedMetrics").value(hasItems(
                        "MESSAGE_READ_RATE", "H5_OPEN_RATE", "CHAT_CAUSAL_ATTRIBUTION")));

        mockMvc.perform(get("/api/v1/reports/wecom-analytics").param("from", FROM).param("to", TO)
                        .cookie(login("manager1").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeType").value("SELF"))
                .andExpect(jsonPath("$.data.aiReply.generatedCount").value(2))
                .andExpect(jsonPath("$.data.conversion.confirmedAppointmentCount").value(2))
                .andExpect(jsonPath("$.data.conversion.checkedInFromConfirmedCount").value(1))
                .andExpect(jsonPath("$.data.conversion.appointmentArrivalRate").value(50.00))
                .andExpect(jsonPath("$.data.conversion.paidOrderCount").value(3))
                .andExpect(jsonPath("$.data.conversion.paidCustomerCount").value(1))
                .andExpect(jsonPath("$.data.conversion.repeatPaidCustomerCount").value(1))
                .andExpect(jsonPath("$.data.conversion.repeatCustomerRate").value(100.00));
        mockMvc.perform(get("/api/v1/reports/wecom-analytics").param("from", FROM).param("to", TO)
                        .cookie(login("lead1").cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.scopeType").value("TEAM"))
                .andExpect(jsonPath("$.data.quickReply.usageCount").value(4))
                .andExpect(jsonPath("$.data.aiReply.generatedCount").value(2));
        mockMvc.perform(get("/api/v1/reports/wecom-analytics").param("from", FROM).param("to", TO)
                        .param("employeeId", "2").cookie(login("admin").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiReply.generatedCount").value(2))
                .andExpect(jsonPath("$.data.conversion.confirmedAppointmentCount").value(2))
                .andExpect(jsonPath("$.data.conversion.paidOrderCount").value(3));
    }

    @Test
    void analyticsRejectsInvalidWindowCrossEmployeeAndAnonymousOrInstitutionAccess() throws Exception {
        mockMvc.perform(get("/api/v1/reports/wecom-analytics").param("from", TO).param("to", FROM)
                        .cookie(login("admin").cookies())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/reports/wecom-analytics").param("employeeId", "0")
                        .cookie(login("admin").cookies())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/reports/wecom-analytics").param("employeeId", "3")
                        .cookie(login("manager1").cookies())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/reports/wecom-analytics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reports/wecom-analytics").cookie(login("inst1").cookies()))
                .andExpect(status().isForbidden());
    }

    private long customer(String suffix, long ownerId, long teamId) {
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,?,?,CONCAT('ANALYTICS-',?),'合成分析客户')", ownerId, teamId, suffix);
        return jdbcTemplate.queryForObject("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                Long.class, "ANALYTICS-" + suffix);
    }

    private long suggestion(String suffix, String key, long customerId, long employeeId, String status,
                            boolean accepted, boolean edited) {
        jdbcTemplate.update("INSERT INTO crm_ai_reply_suggestion (tenant_id,template_id,customer_id,employee_id,scene,"
                        + "facts_snapshot_json,request_hash,idempotency_key,reply_cipher,reason_masked,used_fact_codes_json,"
                        + "provider,model_name,prompt_version,confidence,status,accepted,edited,decision_at,created_at) "
                        + "VALUES (1,(SELECT id FROM crm_quick_reply_template WHERE tenant_id=1 AND code='PROJECT_REMAINING'),"
                        + "?,?,'FOLLOWUP','{}','synthetic-request-hash',?,'synthetic-cipher','合成候选依据','[]',"
                        + "'mock','mock-rules-v2','reply-v1',0.78,?,?,?,?,'2026-11-10 01:00:00')",
                customerId, employeeId, "ai-analytics-" + suffix + "-" + key, status, accepted, edited,
                accepted || "REJECTED".equals(status) ? "2026-11-10 02:00:00" : null);
        return jdbcTemplate.queryForObject("SELECT id FROM crm_ai_reply_suggestion WHERE tenant_id=1 "
                + "AND idempotency_key=?", Long.class, "ai-analytics-" + suffix + "-" + key);
    }

    private void usage(String suffix, String key, long templateId, long customerId, Long suggestionId,
                       String sendStatus, boolean edited) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO crm_quick_reply_usage SET tenant_id=1,"
                    + "template_id=?,ai_suggestion_id=?,employee_id=?,customer_id=?,external_userid='wm-synthetic-analytics',"
                    + "scene='FOLLOWUP',original_content='合成话术原文',final_content='合成最终话术',edited=?,"
                    + "send_status=?,send_time='2026-11-10 03:00:00',idempotency_key=?,created_at='2026-11-10 03:00:00'");
            statement.setLong(1, templateId);
            statement.setObject(2, suggestionId, java.sql.Types.BIGINT);
            statement.setLong(3, 2L);
            statement.setLong(4, customerId);
            statement.setBoolean(5, edited);
            statement.setString(6, sendStatus);
            statement.setString(7, "usage-analytics-" + suffix + "-" + key);
            return statement;
        });
    }

    private void appointment(String suffix, String key, long customerId, boolean checkedIn) {
        Long ownerId = jdbcTemplate.queryForObject("SELECT owner_staff_id FROM crm_customer WHERE tenant_id=1 AND id=?",
                Long.class, customerId);
        Long teamId = jdbcTemplate.queryForObject("SELECT team_id FROM crm_customer WHERE tenant_id=1 AND id=?",
                Long.class, customerId);
        jdbcTemplate.update("INSERT INTO crm_appointment (tenant_id,appointment_no,customer_id,institution_id,"
                        + "owner_staff_id,team_id,scheduled_at,status,created_by,created_at) VALUES (1,?,?,1,?,?,"
                        + "'2026-11-12 02:00:00',?,2,'2026-11-10 00:00:00')",
                "appointment-analytics-" + suffix + "-" + key, customerId, ownerId, teamId, checkedIn ? "CHECKED_IN" : "NO_SHOW");
        long appointmentId = jdbcTemplate.queryForObject("SELECT id FROM crm_appointment WHERE tenant_id=1 "
                + "AND appointment_no=?", Long.class, "appointment-analytics-" + suffix + "-" + key);
        jdbcTemplate.update("INSERT INTO crm_appointment_history (tenant_id,appointment_id,from_status,to_status,"
                + "actor_user_id,reason,created_at) VALUES (1,?,'PENDING_INSTITUTION','CONFIRMED',8,"
                + "'合成机构确认','2026-11-10 04:00:00')", appointmentId);
        if (checkedIn) {
            jdbcTemplate.update("INSERT INTO crm_appointment_history (tenant_id,appointment_id,from_status,to_status,"
                    + "actor_user_id,reason,created_at) VALUES (1,?,'CONFIRMED','CHECKED_IN',8,"
                    + "'合成实际到院','2026-11-11 04:00:00')", appointmentId);
        } else {
            jdbcTemplate.update("INSERT INTO crm_appointment_history (tenant_id,appointment_id,from_status,to_status,"
                    + "actor_user_id,reason,created_at) VALUES (1,?,'CONFIRMED','NO_SHOW',8,"
                    + "'合成未到院','2026-11-11 04:00:00')", appointmentId);
        }
    }

    private void paidOrder(String suffix, String key, long customerId, long ownerId, int payments) {
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "source_system,order_type_code,status,receiver_type,payable_fen,paid_fen,paid_at) "
                        + "VALUES (1,?,?,?,?,'CRM','REGULAR','PAID','PLATFORM',1000,1000,'2026-11-10 05:00:00')",
                "order-analytics-" + suffix + "-" + key, customerId, ownerId, ownerId);
        long orderId = jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE tenant_id=1 AND order_no=?",
                Long.class, "order-analytics-" + suffix + "-" + key);
        for (int index = 0; index < payments; index++) {
            jdbcTemplate.update("INSERT INTO crm_payment_txn (tenant_id,order_id,payment_channel,receiver_type,"
                    + "amount_fen,status,confirmed_at,verification_method) VALUES (1,?,'MANUAL_REVIEW','PLATFORM',"
                    + "1000,'CONFIRMED','2026-11-10 05:00:00','MANUAL_REVIEW')", orderId);
        }
    }

    private void pendingOrder(String suffix, long customerId) {
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                + "source_system,order_type_code,status,receiver_type,payable_fen) VALUES (1,?,?,2,2,"
                + "'CRM','REGULAR','PENDING_PAYMENT','PLATFORM',1000)", "order-pending-analytics-" + suffix, customerId);
    }
}
