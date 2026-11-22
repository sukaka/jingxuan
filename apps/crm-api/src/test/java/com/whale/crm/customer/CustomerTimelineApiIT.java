package com.whale.crm.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerTimelineApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void timelineAggregatesDomainSummariesWithStableCursorAndNoChatText() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager2 = login("manager2");
        long customerId = customer(admin);
        Instant base = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        followup(admin, customerId, base);
        mockMvc.perform(admin.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", customerId, "taskType", "FOLLOWUP",
                                "title", "V33统一时间线任务", "description", "仅任务摘要"))))
                .andExpect(status().isCreated());
        domainFixtures(customerId, base);

        Set<String> types = new LinkedHashSet<>();
        Set<String> eventKeys = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/api/v1/customers/" + customerId + "/timeline?size=3"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            String body = mockMvc.perform(get(path).cookie(admin.cookies()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andReturn().getResponse().getContentAsString();
            JsonNode data = objectMapper.readTree(body).at("/data");
            data.withArray("items").forEach(item -> {
                types.add(item.at("/type").asText());
                assertThat(eventKeys.add(item.at("/eventKey").asText())).isTrue();
                assertThat(item.at("/title").asText()).isNotBlank();
            });
            assertThat(body).doesNotContain("SECRET-CHAT-FULL-TEXT");
            cursor = data.at("/nextCursor").isNull() ? null : data.at("/nextCursor").asText();
            pages++;
            assertThat(pages).isLessThan(10);
        } while (cursor != null);

        assertThat(types).contains("FOLLOWUP", "INTENT", "BLOCKER", "NEXT_FOLLOWUP", "TASK",
                "OWNERSHIP", "ORDER", "APPOINTMENT", "REDEMPTION", "REFUND", "TIER", "COMPLAINT");
        mockMvc.perform(get("/api/v1/customers/{id}/timeline", customerId).cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/{id}/timeline?cursor=not-a-cursor", customerId)
                        .cookie(admin.cookies()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void timelineOmitsUnauthorizedDomainSectionsWithoutReturningForbidden() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies finance = login("finance1");
        AuthCookies operation = login("operation1");
        long customerId = customer(admin);
        Instant base = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        followup(admin, customerId, base);
        mockMvc.perform(admin.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", customerId, "taskType", "FOLLOWUP",
                                "title", "H-SEC-09权限过滤任务", "description", "不应暴露给无task:read账号"))))
                .andExpect(status().isCreated());
        domainFixtures(customerId, base);

        Set<String> financeTypes = timelineTypes(finance, customerId);
        assertThat(financeTypes).contains("OWNERSHIP", "TIER", "ORDER", "REDEMPTION", "REFUND");
        assertThat(financeTypes).doesNotContain("FOLLOWUP", "INTENT", "BLOCKER", "NEXT_FOLLOWUP",
                "TASK", "APPOINTMENT", "COMPLAINT");

        Set<String> operationTypes = timelineTypes(operation, customerId);
        assertThat(operationTypes).contains("OWNERSHIP", "TIER", "ORDER", "COMPLAINT");
        assertThat(operationTypes).doesNotContain("FOLLOWUP", "INTENT", "BLOCKER", "NEXT_FOLLOWUP",
                "TASK", "APPOINTMENT", "REDEMPTION", "REFUND");
    }

    private Set<String> timelineTypes(AuthCookies actor, long customerId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/customers/{id}/timeline?size=50", customerId)
                        .cookie(actor.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andReturn().getResponse().getContentAsString();
        Set<String> types = new LinkedHashSet<>();
        objectMapper.readTree(body).at("/data/items").forEach(item -> types.add(item.at("/type").asText()));
        return types;
    }

    private long customer(AuthCookies admin) throws Exception {
        String body = mockMvc.perform(admin.withAuth(post("/api/v1/customers"))
                        .header("Idempotency-Key", "v33-timeline-customer-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "V33-timeline", "ownerStaffId", 2))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private void followup(AuthCookies admin, long customerId, Instant contactAt) throws Exception {
        mockMvc.perform(admin.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", "v33-timeline-followup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", customerId, "channelCode", "PHONE",
                                "contactAt", contactAt.toString(), "summary", "<img src=x onerror=alert(1)>跟进摘要",
                                "intentProject", "基础水光", "estimatedAmountFen", 100,
                                "nextFollowupAt", contactAt.plus(1, ChronoUnit.DAYS).toString(),
                                "blockerNote", "价格卡点"))))
                .andExpect(status().isCreated());
    }

    private void domainFixtures(long customerId, Instant base) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO crm_customer_owner_history (tenant_id,customer_id,old_owner_id,new_owner_id,"
                        + "changed_by,effective_at,reason,created_at) VALUES (1,?,2,3,1,?,?,?)",
                customerId, ts(base.plusSeconds(10)), "V33归属调整", ts(base.plusSeconds(10)));
        jdbcTemplate.update("INSERT INTO crm_customer_tier_history (tenant_id,customer_id,from_tier_code,to_tier_code,"
                        + "change_type,actor_user_id,reason,effective_at,created_at) VALUES (1,?,'B','A',"
                        + "'MANUAL_ADJUST',1,'V33等级调整',?,?)", customerId, ts(base.plusSeconds(20)),
                ts(base.plusSeconds(20)));
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "source_system,order_type_code,status,receiver_type,list_amount_fen,discount_amount_fen,"
                        + "payable_fen,paid_fen,refunded_fen,ordered_at,paid_at,created_at) VALUES "
                        + "(1,?,?,2,2,'CRM','REGULAR','PAID','PLATFORM',100,0,100,100,0,?,?,?)",
                "V33-TL-O-" + suffix, customerId, ts(base.plusSeconds(30)), ts(base.plusSeconds(30)),
                ts(base.plusSeconds(30)));
        Long orderId = jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE order_no=?",
                Long.class, "V33-TL-O-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_appointment (tenant_id,appointment_no,customer_id,institution_id,"
                        + "owner_staff_id,team_id,scheduled_at,status,created_by,created_at) VALUES "
                        + "(1,?,?,1,2,1,?,'CONFIRMED',1,?)", "V33-TL-A-" + suffix, customerId,
                ts(base.plusSeconds(40)), ts(base.plusSeconds(40)));
        Long appointmentId = jdbcTemplate.queryForObject("SELECT id FROM crm_appointment WHERE appointment_no=?",
                Long.class, "V33-TL-A-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_redemption (tenant_id,appointment_id,customer_id,institution_id,"
                        + "owner_staff_id,team_id,service_at,evidence_ref,status,settlement_status,idempotency_key,"
                        + "posted_by,posted_at,created_at) VALUES (1,?,?,1,2,1,?,'V33证据','POSTED',"
                        + "'PENDING_VERIFICATION',?,1,?,?)", appointmentId, customerId, ts(base.plusSeconds(50)),
                "v33-timeline-redemption-" + suffix, ts(base.plusSeconds(50)), ts(base.plusSeconds(50)));
        jdbcTemplate.update("INSERT INTO crm_refund (tenant_id,refund_no,order_id,customer_id,requested_fen,"
                        + "approved_fen,succeeded_fen,reason_code,status,requested_by,created_at) "
                        + "VALUES (1,?,?,?,100,100,100,'OTHER','SUCCEEDED',1,?)",
                "V33-TL-R-" + suffix, orderId, customerId, ts(base.plusSeconds(60)));
        jdbcTemplate.update("INSERT INTO crm_complaint_hit (tenant_id,customer_id,owner_staff_id_snapshot,"
                        + "team_id_snapshot,source_type,source_id,keyword_snapshot,evidence_masked,status,routing_status,"
                        + "recipient_source_snapshot,policy_version_snapshot,created_at) VALUES "
                        + "(1,?,2,1,'FOLLOWUP',?,'V33关键词','SECRET-CHAT-FULL-TEXT','CONVERTED','ROUTED',"
                        + "'TEAM_LEADER',0,?)", customerId, "v33-hit-" + suffix, ts(base.plusSeconds(70)));
        Long hitId = jdbcTemplate.queryForObject("SELECT id FROM crm_complaint_hit WHERE source_id=?",
                Long.class, "v33-hit-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_complaint_case (tenant_id,complaint_case_no,complaint_hit_id,customer_id,"
                        + "owner_staff_id_snapshot,team_id_snapshot,recipient_source_snapshot,status,resolution_masked,"
                        + "created_by,created_at) VALUES (1,?,?,?,2,1,'TEAM_LEADER','RESOLVED','脱敏处理结论',1,?)",
                "V33-TL-C-" + suffix, hitId, customerId, ts(base.plusSeconds(70)));
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
