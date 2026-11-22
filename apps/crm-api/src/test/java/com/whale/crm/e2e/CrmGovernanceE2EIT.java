package com.whale.crm.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.followup.application.FollowupNotificationScheduler;
import com.whale.crm.followup.domain.FollowupAutomationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F8 CRM 治理端到端闭环：
 * - H-SEC-09 #4：GET /api/v1/risks 只读 vs POST /api/v1/risks/sync -> claim/resolve/close + 重同步不重开 + OWNER/OPERATION 可见性 + 租户2隔离
 * - H-SEC-09 #2：最小 customer:read 账号在 /customers/{id}/timeline 跨 cursor 分页(size=1)仅见 OWNERSHIP + TIER
 * - H-SEC-09 #5：lead1(TEAM 1) scan 仅建一组候选、忽略二组/跨组 -> review -> confirm -> 从客户 404 + 主客户时间线迁移；lead1 403 改删他人分群
 * - H-SEC-09 #6 & H-BUG-09：operationlead1 更新回访策略、无 ALL/write 403 拒批、manager1 HTTP 创建 25+ 逾期任务 -> 调度器无饥饿推送 -> GET /notifications
 */
@SpringBootTest(properties = {
        "app.followup.automation.scheduler-enabled=false",
        "app.exports.worker.enabled=false",
        "app.outbox.relay.enabled=false"
})
class CrmGovernanceE2EIT extends AbstractE2EIT {

    @Autowired
    private FollowupAutomationRepository followupRepository;

    @Autowired
    private FollowupNotificationScheduler notificationScheduler;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void riskReadOnlySyncLifecycleVisibilityAndTenantIsolation() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies owner = login("owner1");
        AuthCookies operation = login("operation1");
        AuthCookies operationLead = login("operationlead1");

        String suffix = shortUuid("rsk");
        String shopId = "E2E-RISK-SHOP-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_youzan_event (tenant_id,shop_id,event_id,event_type,external_order_no,"
                        + "payload_masked_json,payload_cipher,signature_valid,source_system,process_status,process_attempts,received_at) "
                        + "VALUES (1,?,?,'ORDER_PAID',?,'{}','synthetic-cipher',1,'WEBHOOK','PAYMENT_MISMATCH',1,CURRENT_TIMESTAMP(3))",
                shopId, "E2E-RISK-EVT-" + suffix, "E2E-RISK-EXT-" + suffix);
        long eventId = queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? AND event_id=?",
                shopId, "E2E-RISK-EVT-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_youzan_sync_issue (tenant_id,event_id,issue_type,status,detail_masked,attempts) "
                + "VALUES (1,?,'PAYMENT_MISMATCH','OPEN','E2E风控同步异常 13800138000',1)", eventId);
        long issueId = queryLong("SELECT id FROM crm_youzan_sync_issue WHERE tenant_id=1 AND event_id=?", eventId);

        // Tenant 2 issue should remain completely isolated
        String t2Shop = "E2E-RISK-T2-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_youzan_event (tenant_id,shop_id,event_id,event_type,external_order_no,"
                        + "payload_masked_json,payload_cipher,signature_valid,source_system,process_status,process_attempts,received_at) "
                        + "VALUES (2,?,?,'ORDER_PAID',?,'{}','synthetic-cipher',1,'WEBHOOK','PAYMENT_MISMATCH',1,CURRENT_TIMESTAMP(3))",
                t2Shop, "E2E-RISK-T2EVT-" + suffix, "E2E-RISK-T2EXT-" + suffix);
        long t2EventId = queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=2 AND shop_id=?", t2Shop);
        jdbcTemplate.update("INSERT INTO crm_youzan_sync_issue (tenant_id,event_id,issue_type,status,detail_masked,attempts) "
                + "VALUES (2,?,'PAYMENT_MISMATCH','OPEN','二租户异常',1)", t2EventId);
        long t2IssueId = queryLong("SELECT id FROM crm_youzan_sync_issue WHERE tenant_id=2 AND event_id=?", t2EventId);

        // GET /api/v1/risks is strictly read-only
        String filter = "/api/v1/risks?sourceType=YOUZAN_SYNC_ISSUE&sourceRef=" + issueId;
        assertThat(data(get(filter).cookie(owner.cookies())).at("/total").asInt()).isZero();
        assertThat(data(get(filter).cookie(operation.cookies())).at("/total").asInt()).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_risk_case WHERE tenant_id=1 AND source_type='YOUZAN_SYNC_ISSUE' "
                + "AND source_ref=?", Long.toString(issueId))).isZero();

        // OWNER has risk:read + ALL, cannot sync (403); OPERATION has risk:write + ALL, can sync
        mockMvc.perform(owner.withAuth(post("/api/v1/risks/sync"))
                        .header("Idempotency-Key", uniqueKey("e2e-risk-owner-deny"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        JsonNode synced = data(operation.withAuth(post("/api/v1/risks/sync"))
                .header("Idempotency-Key", uniqueKey("e2e-risk-sync"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(synced.at("/createdCount").asInt()).isGreaterThanOrEqualTo(1);
        // Tenant 2 issue was NOT synced into Tenant 1
        assertThat(queryInt("SELECT COUNT(*) FROM crm_risk_case WHERE tenant_id=1 AND source_type='YOUZAN_SYNC_ISSUE' "
                + "AND source_ref=?", Long.toString(t2IssueId))).isZero();

        JsonNode listed = data(get(filter).cookie(owner.cookies()));
        assertThat(listed.at("/total").asInt()).isEqualTo(1);
        assertThat(listed.toString()).doesNotContain("13800138000");
        long riskId = listed.at("/items/0/id").asLong();

        // claim -> resolve -> close -> re-sync does NOT reopen closed case
        data(operationLead.withAuth(post("/api/v1/risks/" + riskId + "/claim"))
                .header("Idempotency-Key", uniqueKey("e2e-risk-claim"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0, "reason", "核查异常"))));
        data(operationLead.withAuth(post("/api/v1/risks/" + riskId + "/resolve"))
                .header("Idempotency-Key", uniqueKey("e2e-risk-resolve"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 1, "resolution", "已确认并处置"))));
        data(operationLead.withAuth(post("/api/v1/risks/" + riskId + "/close"))
                .header("Idempotency-Key", uniqueKey("e2e-risk-close"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 2, "reason", "结案"))));

        jdbcTemplate.update("UPDATE crm_youzan_sync_issue SET detail_masked='重试再次失败 13900139000', attempts=attempts+1 "
                + "WHERE tenant_id=1 AND id=?", issueId);
        data(admin.withAuth(post("/api/v1/risks/sync"))
                .header("Idempotency-Key", uniqueKey("e2e-risk-resync"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        JsonNode afterResync = data(get("/api/v1/risks/" + riskId).cookie(owner.cookies()));
        assertThat(afterResync.at("/riskCase/status").asText()).isEqualTo("CLOSED");
        assertThat(afterResync.at("/history").size()).isEqualTo(4);

        // Tenant 2 risk case is 404 to Tenant 1 admin
        String t2CaseNo = shortUuid("E2E-T2R").toUpperCase();
        jdbcTemplate.update("INSERT INTO crm_risk_case (tenant_id,risk_case_no,risk_type,severity,source_type,source_ref,"
                + "status,evidence) VALUES (2,?,'SECURITY','HIGH','TEST_FIXTURE',?,'OPEN','二租户风险')", t2CaseNo, suffix);
        long t2RiskId = queryLong("SELECT id FROM crm_risk_case WHERE tenant_id=2 AND risk_case_no=?", t2CaseNo);
        mockMvc.perform(get("/api/v1/risks/" + t2RiskId).cookie(admin.cookies()))
                .andExpect(status().isNotFound());
    }

    @Test
    void minimalCustomerReadActorSeesOnlyOwnershipAndTierAcrossCursorPages() throws Exception {
        AuthCookies admin = login("admin");
        String roleCode = shortCode("E2E_CMIN");
        long roleId = data(admin.withAuth(post("/api/v1/roles"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("roleCode", roleCode, "roleName", "仅客户只读角色"))))
                .at("/id").asLong();
        mockMvc.perform(admin.withAuth(put("/api/v1/roles/" + roleId + "/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("permissionCodes", List.of("customer:read")))))
                .andExpect(status().isOk());

        String minUsername = shortUuid("e2e_cust_min");
        long minUserId = data(admin.withAuth(post("/api/v1/users"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", minUsername, "displayName", "最小客户只读号",
                        "password", DEV_PASSWORD, "userType", "EMPLOYEE", "teamId", 1,
                        "roleIds", List.of(roleId)))))
                .at("/id").asLong();
        jdbcTemplate.update("INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type) VALUES (1,?,'customer','ALL')",
                minUserId);
        AuthCookies minActor = loginWithTenantCode(minUsername, DEV_PASSWORD, "WHALE", 1L);

        long customerId = data(admin.withAuth(post("/api/v1/customers"))
                .header("Idempotency-Key", uniqueKey("e2e-tl-cust"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nickname", shortUuid("TLMin"), "ownerStaffId", 2))))
                .at("/id").asLong();

        Instant base = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        mockMvc.perform(admin.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", uniqueKey("e2e-tl-fu"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", customerId, "channelCode", "PHONE",
                                "contactAt", base.toString(), "summary", "绝密跟进摘要",
                                "intentProject", "水光针"))))
                .andExpect(status().isCreated());
        mockMvc.perform(admin.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", customerId, "taskType", "FOLLOWUP",
                                "title", "绝密待办", "description", "不应返回"))))
                .andExpect(status().isCreated());

        // Add OWNERSHIP, TIER, ORDER, COMPLAINT events
        jdbcTemplate.update("INSERT INTO crm_customer_owner_history (tenant_id,customer_id,old_owner_id,new_owner_id,"
                        + "changed_by,effective_at,reason,created_at) VALUES (1,?,2,3,1,?,?,?)",
                customerId, Timestamp.from(base.plusSeconds(10)), "归属流转", Timestamp.from(base.plusSeconds(10)));
        jdbcTemplate.update("INSERT INTO crm_customer_tier_history (tenant_id,customer_id,from_tier_code,to_tier_code,"
                        + "change_type,actor_user_id,reason,effective_at,created_at) VALUES (1,?,'D','C',"
                        + "'MANUAL_ADJUST',1,'等级升级',?,?)", customerId,
                Timestamp.from(base.plusSeconds(20)), Timestamp.from(base.plusSeconds(20)));
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "source_system,order_type_code,status,receiver_type,payable_fen,paid_fen,refunded_fen,ordered_at,paid_at,created_at) "
                        + "VALUES (1,?,?,2,2,'CRM','REGULAR','PAID','PLATFORM',100,100,0,?,?,?)",
                shortUuid("E2E-TL-ORD"), customerId, Timestamp.from(base.plusSeconds(30)),
                Timestamp.from(base.plusSeconds(30)), Timestamp.from(base.plusSeconds(30)));

        Set<String> minTypes = new LinkedHashSet<>();
        Set<String> keys = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = "/api/v1/customers/" + customerId + "/timeline?size=1"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = data(get(url).cookie(minActor.cookies()));
            for (JsonNode item : page.withArray("items")) {
                minTypes.add(item.at("/type").asText());
                assertThat(keys.add(item.at("/eventKey").asText())).isTrue();
            }
            cursor = page.at("/nextCursor").isNull() ? null : page.at("/nextCursor").asText();
            pages++;
            assertThat(pages).isLessThan(10);
        } while (cursor != null);

        assertThat(minTypes).containsExactlyInAnyOrder("OWNERSHIP", "TIER");
    }

    @Test
    void leadOneScopedMergeScanConfirmTimelineMigrationAndTeamSegmentGuard() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies lead1 = login("lead1");
        AuthCookies manager1 = login("manager1");

        String uniqueMobileT1 = "137" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
        String uniqueMobileT2 = "136" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
        String uniqueMobileCross = "135" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);

        long t1Primary = createCustomer(admin, shortUuid("T1Prim"), uniqueMobileT1, 2);
        long t1Secondary = createCustomer(admin, shortUuid("T1Sec"), uniqueMobileT1, 2);
        long t2A = createCustomer(admin, shortUuid("T2A"), uniqueMobileT2, 3);
        long t2B = createCustomer(admin, shortUuid("T2B"), uniqueMobileT2, 3);
        long cross1 = createCustomer(admin, shortUuid("Cross1"), uniqueMobileCross, 2);
        long cross2 = createCustomer(admin, shortUuid("Cross2"), uniqueMobileCross, 3);

        // Attach followup + task on t1Secondary so we verify migration into primary timeline
        String fuSummary = shortUuid("E2E合并迁移跟进");
        mockMvc.perform(manager1.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", uniqueKey("e2e-merge-fu"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", t1Secondary, "channelCode", "PHONE",
                                "contactAt", Instant.now().minusSeconds(120).toString(),
                                "summary", fuSummary))))
                .andExpect(status().isCreated());
        String migratedTaskTitle = shortUuid("E2E合并迁移任务");
        mockMvc.perform(manager1.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", t1Secondary, "taskType", "FOLLOWUP",
                                "title", migratedTaskTitle))))
                .andExpect(status().isCreated());

        // lead1 (TEAM 1) scans -> creates Team 1 pair, skips Team 2 & cross-team
        JsonNode scan = data(lead1.withAuth(post("/api/v1/customer-merge-candidates/scan")));
        long mergeCaseId = -1L;
        List<Long> scannedIds = new ArrayList<>();
        for (JsonNode item : scan.withArray("items")) {
            long pId = item.at("/primaryCustomerId").asLong();
            long sId = item.at("/secondaryCustomerId").asLong();
            scannedIds.add(pId);
            scannedIds.add(sId);
            if (pId == t1Primary && sId == t1Secondary) {
                mergeCaseId = item.at("/id").asLong();
            }
        }
        assertThat(mergeCaseId).isPositive();
        assertThat(scannedIds).doesNotContain(t2A, t2B, cross1, cross2);

        // review -> confirm
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-merges/" + mergeCaseId + "/review"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-merges/" + mergeCaseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"E2E核实同一客户\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // Secondary returns 404, primary timeline includes migrated followup & task
        mockMvc.perform(get("/api/v1/customers/" + t1Secondary).cookie(lead1.cookies()))
                .andExpect(status().isNotFound());
        String primaryTimeline = mockMvc.perform(get("/api/v1/customers/" + t1Primary + "/timeline?size=50")
                        .cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(primaryTimeline).contains(fuSummary, migratedTaskTitle);

        // H-SEC-09 #5: lead1 (TEAM scope, NOT ALL) receives 403 updating/deleting manager1's segment
        long m1SegId = data(manager1.withAuth(post("/api/v1/customer-segments"))
                .header("Idempotency-Key", uniqueKey("e2e-m1-seg"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", shortUuid("M1Seg"), "filters", Map.of("cityCodes", List.of("BJ"))))))
                .at("/id").asLong();
        mockMvc.perform(lead1.withAuth(patch("/api/v1/customer-segments/" + m1SegId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "越权改名", "filters", Map.of(), "version", 0))))
                .andExpect(status().isForbidden());
        mockMvc.perform(lead1.withAuth(delete("/api/v1/customer-segments/" + m1SegId + "?version=0")))
                .andExpect(status().isForbidden());
    }

    @Test
    void followupPolicyBatchGuardsAndTwentyFiveOverdueTasksSchedulerToNotifications() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies operationLead = login("operationlead1");
        AuthCookies lead1 = login("lead1");
        AuthCookies manager1 = login("manager1");

        // H-SEC-09 #6: operationlead1 updates policy (followup:policy:manage)
        long policyVersion = data(get("/api/v1/followup-automation/policy").cookie(admin.cookies()))
                .at("/version").asLong();
        mockMvc.perform(operationLead.withAuth(put("/api/v1/followup-automation/policy"))
                        .header("Idempotency-Key", uniqueKey("e2e-fu-pol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "defaultIntervalDays", 14,
                                "intentOverrides", List.of(Map.of("intentLevel", "HIGH", "intervalDays", 3)),
                                "version", policyVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intentOverrides[0].intervalDays").value(3));

        // operationlead1 (no followup:write) & lead1 (TEAM scope, not ALL) get 403 on monthly batch create
        mockMvc.perform(operationLead.withAuth(post("/api/v1/followup-automation/batches"))
                        .header("Idempotency-Key", uniqueKey("e2e-fu-oplead-deny"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderMonth\":\"2099-03\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(lead1.withAuth(post("/api/v1/followup-automation/batches"))
                        .header("Idempotency-Key", uniqueKey("e2e-fu-lead-deny"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderMonth\":\"2099-03\"}"))
                .andExpect(status().isForbidden());

        // Drain any pre-existing due tasks in shared DB so our 25 tasks run deterministically
        Instant drainNow = Instant.now();
        while (!followupRepository.findDueTasks(1, drainNow, 20).isEmpty()) {
            notificationScheduler.runOnce();
            drainNow = Instant.now();
        }

        // H-BUG-09: manager1 creates 25 overdue followups via HTTP POST /api/v1/followups -> FOLLOWUP tasks
        List<Long> taskIds = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            long customerId = data(manager1.withAuth(post("/api/v1/customers"))
                    .header("Idempotency-Key", uniqueKey("e2e-fu-cust-" + i))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("nickname", shortUuid("DueTaskCust" + i)))))
                    .at("/id").asLong();
            Instant dueAt = Instant.now().minus(1, ChronoUnit.HOURS).minusSeconds(i);
            data(manager1.withAuth(post("/api/v1/followups"))
                    .header("Idempotency-Key", uniqueKey("e2e-fu-rec-" + i))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of(
                            "customerId", customerId,
                            "channelCode", "PHONE",
                            "contactAt", Instant.now().minus(2, ChronoUnit.DAYS).toString(),
                            "nextFollowupAt", dueAt.toString(),
                            "summary", shortUuid("E2E逾期跟进-" + i)))));
            long taskId = queryLong(
                    "SELECT id FROM crm_task WHERE tenant_id=1 AND customer_id=? AND source_type='FOLLOWUP' ORDER BY id DESC LIMIT 1",
                    customerId);
            taskIds.add(taskId);
        }

        int firstPass = notificationScheduler.runOnce();
        assertThat(firstPass).isEqualTo(20);
        int secondPass = notificationScheduler.runOnce();
        assertThat(secondPass).isEqualTo(5);

        // No starvation: third pass finds 0 remaining due tasks
        assertThat(notificationScheduler.runOnce()).isZero();

        JsonNode notifications = data(get("/api/v1/notifications?notificationType=FOLLOWUP_DUE&size=100")
                .cookie(manager1.cookies()));
        Set<String> deliveredTaskIds = new HashSet<>();
        for (JsonNode item : notifications.withArray("items")) {
            if ("TASK".equals(item.at("/sourceType").asText())) {
                deliveredTaskIds.add(item.at("/sourceId").asText());
            }
        }
        for (Long taskId : taskIds) {
            assertThat(deliveredTaskIds).contains(Long.toString(taskId));
        }

        // Restore admin policy override cleanly
        long latestVersion = data(get("/api/v1/followup-automation/policy").cookie(admin.cookies()))
                .at("/version").asLong();
        mockMvc.perform(admin.withAuth(put("/api/v1/followup-automation/policy"))
                        .header("Idempotency-Key", uniqueKey("e2e-fu-pol-restore"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("defaultIntervalDays", 14, "intentOverrides", List.of(),
                                "version", latestVersion))))
                .andExpect(status().isOk());
    }

    private long createCustomer(AuthCookies actor, String nickname, String mobile, long ownerStaffId)
            throws Exception {
        return data(actor.withAuth(post("/api/v1/customers"))
                .header("Idempotency-Key", uniqueKey("e2e-cust"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nickname", nickname, "mobile", mobile, "ownerStaffId", ownerStaffId))))
                .at("/id").asLong();
    }
}
