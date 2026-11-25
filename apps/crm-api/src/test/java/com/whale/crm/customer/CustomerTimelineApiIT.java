package com.whale.crm.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.complaint.infrastructure.mapper.ComplaintMapper;
import com.whale.crm.customer.domain.CustomerOwnerHistoryRepository;
import com.whale.crm.customer.domain.FollowupRepository;
import com.whale.crm.customer.domain.TaskRepository;
import com.whale.crm.customer.tier.domain.CustomerTierRepository;
import com.whale.crm.fulfillment.domain.FulfillmentRepository;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.refund.infrastructure.mapper.RefundMapper;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerTimelineApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private CustomerOwnerHistoryRepository ownerHistoryRepository;
    @MockitoSpyBean
    private FollowupRepository followupRepository;
    @MockitoSpyBean
    private TaskRepository taskRepository;
    @MockitoSpyBean
    private TradingRepository tradingRepository;
    @MockitoSpyBean
    private FulfillmentRepository fulfillmentRepository;
    @MockitoSpyBean
    private RefundMapper refundRepository;
    @MockitoSpyBean
    private CustomerTierRepository tierRepository;
    @MockitoSpyBean
    private ComplaintMapper complaintRepository;

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

    @Test
    void timelineHandlesSameOccurredAtTiesWithinAndAcrossAllNineBranches() throws Exception {
        AuthCookies admin = login("admin");
        long customerId = customer(admin);
        Instant base = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        // 3 followups at exact base (8 events: 3 FOLLOWUP + 2 INTENT + 2 NEXT_FOLLOWUP + 1 BLOCKER)
        insertFollowupRow(customerId, base, "F1", "热玛吉", base.plusSeconds(100), "卡点1");
        insertFollowupRow(customerId, base, "F2", "水光针", null, null);
        insertFollowupRow(customerId, base, "F3", null, base.plusSeconds(200), null);
        // 3 tasks, 3 orders, 3 refunds, 2 ownerships, 2 tiers, 2 appointments, 2 redemptions, 2 complaints = 19 events
        for (int i = 0; i < 3; i++) {
            insertTaskRow(customerId, 2L, 1L, "同刻任务-" + i, base);
            long orderId = insertOrderRow(customerId, base, "TIE-O-" + i);
            insertRefundRow(customerId, orderId, base, "TIE-R-" + i);
        }
        for (int i = 0; i < 2; i++) {
            jdbcTemplate.update("INSERT INTO crm_customer_owner_history (tenant_id,customer_id,old_owner_id,"
                            + "new_owner_id,changed_by,effective_at,reason,created_at) VALUES (1,?,2,3,1,?,?,?)",
                    customerId, ts(base), "同刻归属-" + i, ts(base));
            jdbcTemplate.update("INSERT INTO crm_customer_tier_history (tenant_id,customer_id,from_tier_code,"
                            + "to_tier_code,change_type,actor_user_id,reason,effective_at,created_at) "
                            + "VALUES (1,?,'B','A','MANUAL_ADJUST',1,?,?,?)",
                    customerId, "同刻等级-" + i, ts(base), ts(base));
            long apptId = insertAppointmentRow(customerId, base, "TIE-A-" + i);
            insertRedemptionRow(customerId, apptId, base, "TIE-RED-" + i);
            insertComplaintRow(customerId, base, "TIE-C-" + i);
        }

        List<String> referenceKeys = collectAllEventKeys(admin, customerId, 100);
        assertThat(referenceKeys).hasSize(27);
        assertThat(new HashSet<>(referenceKeys)).hasSize(27);

        for (int size : new int[]{1, 2, 3, 5}) {
            List<String> pagedKeys = collectAllEventKeys(admin, customerId, size);
            assertThat(pagedKeys)
                    .as("size=%d 分页应与全量顺序完全一致", size)
                    .containsExactlyElementsOf(referenceKeys);
        }
    }

    @Test
    void timelineSplitsMultipleFollowupsAtSameOccurredAtWithComplaintBetweenSubtypes() throws Exception {
        AuthCookies admin = login("admin");
        long customerId = customer(admin);
        Instant base = Instant.now().minus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        // 4 followups sharing exact same occurredAt=base (4 + 3 + 3 + 3 = 13 events) + 1 COMPLAINT at base (between FOLLOWUP and BLOCKER)
        insertFollowupRow(customerId, base, "SF1", "项目1", base.plusSeconds(60), "卡点1");
        insertFollowupRow(customerId, base, "SF2", null, base.plusSeconds(120), "卡点2");
        insertFollowupRow(customerId, base, "SF3", "项目3", null, null);
        insertFollowupRow(customerId, base, "SF4", "项目4", base.plusSeconds(180), "卡点4");
        insertComplaintRow(customerId, base, "SPLIT-C");

        List<String> referenceKeys = collectAllEventKeys(admin, customerId, 100);
        assertThat(referenceKeys).hasSize(14);
        assertThat(new HashSet<>(referenceKeys)).hasSize(14);

        for (int size : new int[]{1, 2, 3}) {
            List<String> pagedKeys = collectAllEventKeys(admin, customerId, size);
            assertThat(pagedKeys)
                    .as("多跟进同毫秒+客诉夹心 size=%d 分页不得漏行或重复", size)
                    .containsExactlyElementsOf(referenceKeys);
        }
    }

    @Test
    void timelineEnforcesTaskScopeAndComplaintCustomerIdIsolation() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies admin = login("admin");
        long targetCustomer = customer(admin);
        long otherCustomer = customer(admin);
        Instant base = Instant.now().minus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        insertTaskRow(targetCustomer, 2L, 1L, "SELF-本人任务", base);
        insertTaskRow(targetCustomer, null, 1L, "SELF-团队未分配任务", base.plusSeconds(1));
        insertTaskRow(targetCustomer, 3L, 1L, "SELF-他人任务不应可见", base.plusSeconds(2));

        String targetCaseNo = "TGT-CASE-" + UUID.randomUUID().toString().substring(0, 8);
        insertComplaintRowWithNo(targetCustomer, base, targetCaseNo);
        for (int i = 0; i < 120; i++) {
            insertComplaintRow(otherCustomer, base.plusSeconds(10 + i), "OTH-" + i);
        }

        String body = mockMvc.perform(get("/api/v1/customers/{id}/timeline?size=50", targetCustomer)
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("SELF-本人任务", "SELF-团队未分配任务", targetCaseNo);
        assertThat(body).doesNotContain("SELF-他人任务不应可见", "OTH-");
    }

    @Test
    void timelineExecutesZeroCountQueriesAndComplaintQueryUsesCustomerIndex() throws Exception {
        AuthCookies admin = login("admin");
        long customerId = customer(admin);
        Instant base = Instant.now().minus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        insertFollowupRow(customerId, base, "CNT-F", "水光", base.plusSeconds(60), "卡点");
        insertTaskRow(customerId, 2L, 1L, "CNT-T", base);
        domainFixtures(customerId, base);

        clearInvocations(ownerHistoryRepository, followupRepository, taskRepository, tradingRepository,
                fulfillmentRepository, refundRepository, tierRepository, complaintRepository);

        mockMvc.perform(get("/api/v1/customers/{id}/timeline?size=5", customerId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(5));

        verify(followupRepository, never()).countSearch(anyLong(), any(), any(), anyLong(), any());
        verify(taskRepository, never()).countSearch(anyLong(), any(), any(), anyLong(), any(), any(), any());
        verify(tradingRepository, never()).countOrders(anyLong(), any(), any(), anyLong(), any(), any());
        verify(fulfillmentRepository, never()).countAppointments(anyLong(), any(), any(), any(), any());
        verify(fulfillmentRepository, never()).countRedemptions(anyLong(), any(), any(), any(), any(), any());
        verify(refundRepository, never()).countSearch(anyLong(), any(), any(), any(), any(), any(), anyLong());
        verify(complaintRepository, never()).countCases(any());

        verify(ownerHistoryRepository).findTimelineRowsByCustomerId(anyLong(), anyLong(), any(), any(), any(), anyInt());
        verify(followupRepository).findTimelineEvents(anyLong(), any(), any(), anyLong(), anyLong(),
                any(), any(), any(), any(), any(), any(), anyInt());
        verify(taskRepository).findTimeline(anyLong(), any(), any(), anyLong(), anyLong(),
                any(), any(), any(), anyInt());
        verify(tradingRepository).findTimelineOrders(anyLong(), any(), any(), anyLong(), anyLong(),
                any(), any(), any(), anyInt());
        verify(fulfillmentRepository).findTimelineAppointments(anyLong(), any(), anyLong(),
                any(), any(), any(), anyInt());
        verify(fulfillmentRepository).findTimelineRedemptions(anyLong(), any(), anyLong(),
                any(), any(), any(), anyInt());
        verify(refundRepository).findTimelineRefunds(anyLong(), anyLong(), any(), any(), anyLong(),
                any(), any(), any(), anyInt());
        verify(tierRepository).findTimelineHistory(anyLong(), anyLong(), any(), any(), any(), anyInt());
        verify(complaintRepository).findTimelineCases(any());

        List<Map<String, Object>> plan = jdbcTemplate.queryForList(
                "EXPLAIN SELECT cc.id FROM crm_complaint_case cc "
                        + "JOIN crm_customer c ON c.tenant_id=cc.tenant_id AND c.id=cc.customer_id AND c.deleted=0 "
                        + "WHERE cc.tenant_id=1 AND cc.customer_id=? "
                        + "ORDER BY cc.created_at DESC, cc.id DESC LIMIT 6",
                customerId);
        Map<String, Object> ccRow = plan.stream()
                .filter(row -> "cc".equals(row.get("table")))
                .findFirst()
                .orElseThrow();
        assertThat(ccRow.get("key")).isEqualTo("idx_complaint_case_customer");
        assertThat(ccRow.get("type")).isEqualTo("ref");
    }

    private List<String> collectAllEventKeys(AuthCookies actor, long customerId, int size) throws Exception {
        List<String> keys = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/api/v1/customers/" + customerId + "/timeline?size=" + size
                    + (cursor == null ? "" : "&cursor=" + cursor);
            String body = mockMvc.perform(get(path).cookie(actor.cookies()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode data = objectMapper.readTree(body).at("/data");
            data.withArray("items").forEach(item -> keys.add(item.at("/eventKey").asText()));
            cursor = data.at("/nextCursor").isNull() ? null : data.at("/nextCursor").asText();
            pages++;
            assertThat(pages).isLessThan(100);
        } while (cursor != null);
        return keys;
    }

    private void insertFollowupRow(long customerId, Instant contactAt, String summary,
                                   String intentProject, Instant nextFollowupAt, String blockerNote) {
        jdbcTemplate.update("INSERT INTO crm_followup (tenant_id,customer_id,owner_staff_id,channel_code,"
                        + "contact_at,summary,intent_project,estimated_amount_fen,next_followup_at,blocker_note,"
                        + "created_by,created_at) VALUES (1,?,2,'PHONE',?,?,?,100,?,?,1,?)",
                customerId, ts(contactAt), summary, intentProject,
                nextFollowupAt == null ? null : ts(nextFollowupAt), blockerNote, ts(contactAt));
    }

    private void insertTaskRow(long customerId, Long assigneeUserId, Long teamId, String title, Instant createdAt) {
        jdbcTemplate.update("INSERT INTO crm_task (tenant_id,customer_id,assignee_user_id,original_assignee_user_id,"
                        + "task_team_id,task_type,priority,status,title,description,source_type,created_at) "
                        + "VALUES (1,?,?,?,?, 'FOLLOWUP','MEDIUM','OPEN',?,'摘要','MANUAL',?)",
                customerId, assigneeUserId, assigneeUserId, teamId, title, ts(createdAt));
    }

    private long insertOrderRow(long customerId, Instant createdAt, String suffix) {
        String orderNo = "V33-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "source_system,order_type_code,status,receiver_type,list_amount_fen,discount_amount_fen,"
                        + "payable_fen,paid_fen,refunded_fen,ordered_at,paid_at,created_at) VALUES "
                        + "(1,?,?,2,2,'CRM','REGULAR','PAID','PLATFORM',100,0,100,100,0,?,?,?)",
                orderNo, customerId, ts(createdAt), ts(createdAt), ts(createdAt));
        return jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE order_no=?", Long.class, orderNo);
    }

    private void insertRefundRow(long customerId, long orderId, Instant createdAt, String suffix) {
        String refundNo = "V33-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO crm_refund (tenant_id,refund_no,order_id,customer_id,requested_fen,"
                        + "approved_fen,succeeded_fen,reason_code,status,requested_by,created_at) "
                        + "VALUES (1,?,?,?,100,100,100,'OTHER','SUCCEEDED',1,?)",
                refundNo, orderId, customerId, ts(createdAt));
    }

    private long insertAppointmentRow(long customerId, Instant createdAt, String suffix) {
        String apptNo = "V33-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO crm_appointment (tenant_id,appointment_no,customer_id,institution_id,"
                        + "owner_staff_id,team_id,scheduled_at,status,created_by,created_at) VALUES "
                        + "(1,?,?,1,2,1,?,'CONFIRMED',1,?)",
                apptNo, customerId, ts(createdAt), ts(createdAt));
        return jdbcTemplate.queryForObject("SELECT id FROM crm_appointment WHERE appointment_no=?",
                Long.class, apptNo);
    }

    private void insertRedemptionRow(long customerId, long appointmentId, Instant createdAt, String suffix) {
        String key = "v33-" + suffix + "-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO crm_redemption (tenant_id,appointment_id,customer_id,institution_id,"
                        + "owner_staff_id,team_id,service_at,evidence_ref,status,settlement_status,idempotency_key,"
                        + "posted_by,posted_at,created_at) VALUES (1,?,?,1,2,1,?,'证据','POSTED',"
                        + "'PENDING_VERIFICATION',?,1,?,?)",
                appointmentId, customerId, ts(createdAt), key, ts(createdAt), ts(createdAt));
    }

    private void insertComplaintRow(long customerId, Instant createdAt, String suffix) {
        insertComplaintRowWithNo(customerId, createdAt, "V33-C-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6));
    }

    private void insertComplaintRowWithNo(long customerId, Instant createdAt, String caseNo) {
        String sourceId = "hit-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO crm_complaint_hit (tenant_id,customer_id,owner_staff_id_snapshot,"
                        + "team_id_snapshot,source_type,source_id,keyword_snapshot,evidence_masked,status,routing_status,"
                        + "recipient_source_snapshot,policy_version_snapshot,created_at) VALUES "
                        + "(1,?,2,1,'FOLLOWUP',?,'关键词','脱敏证据','CONVERTED','ROUTED',"
                        + "'TEAM_LEADER',0,?)", customerId, sourceId, ts(createdAt));
        Long hitId = jdbcTemplate.queryForObject("SELECT id FROM crm_complaint_hit WHERE source_id=?",
                Long.class, sourceId);
        jdbcTemplate.update("INSERT INTO crm_complaint_case (tenant_id,complaint_case_no,complaint_hit_id,customer_id,"
                        + "owner_staff_id_snapshot,team_id_snapshot,recipient_source_snapshot,status,resolution_masked,"
                        + "created_by,created_at) VALUES (1,?,?,?,2,1,'TEAM_LEADER','RESOLVED','结论',1,?)",
                caseNo, hitId, customerId, ts(createdAt));
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
