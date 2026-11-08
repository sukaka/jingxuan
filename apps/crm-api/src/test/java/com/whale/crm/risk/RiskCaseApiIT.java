package com.whale.crm.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiskCaseApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthCookies admin;
    private AuthCookies managerWithRiskPermission;
    private AuthCookies institutionWithRiskPermission;

    @BeforeAll
    void actors() throws Exception {
        admin = login("admin");
        managerWithRiskPermission = newRiskActor("EMPLOYEE", "MANAGER");
        institutionWithRiskPermission = newRiskActor("INSTITUTION", "INSTITUTION_STAFF");
    }

    @Test
    void workflowEnforcesTransitionsVersionsMaskingHistoryAuditAndNoDelete() throws Exception {
        String suffix = suffix();
        long id = newRisk(1, "IT-RISK-CASE-" + suffix, "SECURITY", "TEST_FIXTURE", suffix,
                "OPEN", "{\"name\":\"合成姓名\",\"mobile\":\"13800138000\"}");

        JsonNode listed = data(get("/api/v1/risks").param("status", "OPEN").param("severity", "HIGH")
                .param("riskType", "SECURITY").param("keyword", "IT-RISK-CASE-" + suffix).cookie(admin.cookies()));
        assertThat(listed.at("/total").asInt()).isEqualTo(1);
        assertThat(listed.at("/items/0/id").asLong()).isEqualTo(id);
        assertThat(listed.toString()).doesNotContain("合成姓名", "13800138000");
        assertThat(listed.at("/items/0/evidence").asText()).contains("\"mobile\":\"***\"");

        JsonNode detail = data(get("/api/v1/risks/" + id).cookie(admin.cookies()));
        assertThat(detail.at("/riskCase/status").asText()).isEqualTo("OPEN");
        assertThat(detail.at("/history/0/action").asText()).isEqualTo("REGISTER");
        assertThat(detail.at("/history/0/riskCaseId").asLong()).isEqualTo(id);

        mockMvc.perform(admin.withAuth(post("/api/v1/risks/" + id + "/resolve"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0, "resolution", "非法跳转"))))
                .andExpect(status().isConflict());

        JsonNode claimed = data(admin.withAuth(post("/api/v1/risks/" + id + "/claim"))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0, "reason", "开始核查"))));
        assertThat(claimed.at("/riskCase/status").asText()).isEqualTo("ASSIGNED");
        assertThat(claimed.at("/riskCase/ownerUserId").asLong()).isEqualTo(1);
        assertThat(claimed.at("/riskCase/version").asLong()).isEqualTo(1);

        mockMvc.perform(admin.withAuth(post("/api/v1/risks/" + id + "/claim"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0, "reason", "旧版本"))))
                .andExpect(status().isConflict());

        JsonNode resolved = data(admin.withAuth(post("/api/v1/risks/" + id + "/resolve"))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 1, "resolution", "已人工核实并处理"))));
        assertThat(resolved.at("/riskCase/status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.at("/riskCase/resolvedAt").isTextual()).isTrue();

        JsonNode closed = data(admin.withAuth(post("/api/v1/risks/" + id + "/close"))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 2, "reason", "复核完成"))));
        assertThat(closed.at("/riskCase/status").asText()).isEqualTo("CLOSED");
        assertThat(closed.at("/riskCase/version").asLong()).isEqualTo(3);
        assertThat(closed.findValues("action").stream().map(JsonNode::asText))
                .contains("REGISTER", "CLAIM", "RESOLVE", "CLOSE");

        assertThat(jdbcTemplate.queryForList("SELECT action FROM crm_risk_case_history WHERE tenant_id=1 "
                + "AND risk_case_id=? ORDER BY id", String.class, id))
                .containsExactly("REGISTER", "CLAIM", "RESOLVE", "CLOSE");
        assertThat(jdbcTemplate.queryForList("SELECT action_code FROM crm_audit_log WHERE tenant_id=1 "
                        + "AND target_type='RISK_CASE' AND target_id=? ORDER BY id", String.class, Long.toString(id)))
                .containsExactly("RISK_CASE_CLAIM", "RISK_CASE_RESOLVE", "RISK_CASE_CLOSE");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_risk_case_history SET note='非法覆盖' "
                + "WHERE tenant_id=1 AND risk_case_id=?", id)).hasMessageContaining("append only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM crm_risk_case_history "
                + "WHERE tenant_id=1 AND risk_case_id=?", id)).hasMessageContaining("append only");
        assertThat(queryInt("SELECT COUNT(*) FROM crm_risk_case_history WHERE tenant_id=1 AND risk_case_id=?", id))
                .isEqualTo(4);

        mockMvc.perform(admin.withAuth(delete("/api/v1/risks/" + id))).andExpect(status().isNotFound());
        assertThat(queryInt("SELECT COUNT(*) FROM crm_risk_case WHERE tenant_id=1 AND id=?", id)).isEqualTo(1);
        assertThat(queryString("SELECT status FROM crm_risk_case WHERE tenant_id=1 AND id=?", id)).isEqualTo("CLOSED");
    }

    @Test
    void globalAccessIsEnforcedBeyondAnnotationsAndTenantIsAlwaysScoped() throws Exception {
        String suffix = suffix();
        long otherTenantRisk = newRisk(2, "IT-RISK-TENANT-" + suffix, "SECURITY", "TEST_FIXTURE", suffix,
                "OPEN", "跨租户夹具");
        long ownTenantRisk = newRisk(1, "IT-RISK-OWN-" + suffix, "SECURITY", "TEST_FIXTURE", suffix,
                "OPEN", "本租户夹具");

        mockMvc.perform(get("/api/v1/risks").cookie(managerWithRiskPermission.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/risks/" + ownTenantRisk).cookie(institutionWithRiskPermission.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(managerWithRiskPermission.withAuth(post("/api/v1/risks/" + ownTenantRisk + "/claim"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0, "reason", "越权"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/risks/" + otherTenantRisk).cookie(admin.cookies()))
                .andExpect(status().isNotFound());
        JsonNode filtered = data(get("/api/v1/risks").param("sourceRef", suffix).cookie(admin.cookies()));
        assertThat(filtered.at("/total").asInt()).isEqualTo(1);
        assertThat(filtered.at("/items/0/id").asLong()).isEqualTo(ownTenantRisk);
    }

    @Test
    void realYouzanIssueIsTraceableIdempotentAndNeverReopensClosedHistory() throws Exception {
        long issueId = newYouzanIssue("OPEN", "支付异常，手机号13800138000");
        long sourceVersion = queryLong("SELECT version FROM crm_youzan_sync_issue WHERE tenant_id=1 AND id=?", issueId);
        String filter = "/api/v1/risks?sourceType=YOUZAN_SYNC_ISSUE&sourceRef=" + issueId
                + "&riskType=PAYMENT_MISMATCH";

        JsonNode first = data(get(filter).cookie(admin.cookies()));
        assertThat(first.at("/total").asInt()).isEqualTo(1);
        assertThat(first.toString()).doesNotContain("13800138000");
        long riskId = first.at("/items/0/id").asLong();
        assertThat(first.at("/items/0/sourceRef").asText()).isEqualTo(Long.toString(issueId));
        assertThat(first.at("/items/0/status").asText()).isEqualTo("OPEN");
        assertThat(first.at("/items/0/customerId").isMissingNode()).isTrue();
        assertThat(first.at("/items/0/customerName").isMissingNode()).isTrue();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_risk_case_history WHERE tenant_id=1 AND risk_case_id=?", riskId))
                .isEqualTo(1);
        assertThat(queryLong("SELECT version FROM crm_youzan_sync_issue WHERE tenant_id=1 AND id=?", issueId))
                .isEqualTo(sourceVersion);
        assertThat(queryString("SELECT status FROM crm_youzan_sync_issue WHERE tenant_id=1 AND id=?", issueId))
                .isEqualTo("OPEN");

        JsonNode replay = data(get(filter).cookie(admin.cookies()));
        assertThat(replay.at("/total").asInt()).isEqualTo(1);
        assertThat(replay.at("/items/0/id").asLong()).isEqualTo(riskId);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_risk_case_history WHERE tenant_id=1 AND risk_case_id=?", riskId))
                .isEqualTo(1);

        data(admin.withAuth(post("/api/v1/risks/" + riskId + "/claim")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0, "reason", "核查有赞来源"))));
        data(admin.withAuth(post("/api/v1/risks/" + riskId + "/resolve")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 1, "resolution", "来源异常已人工复核"))));
        data(admin.withAuth(post("/api/v1/risks/" + riskId + "/close")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 2, "reason", "保留关闭历史"))));

        jdbcTemplate.update("UPDATE crm_youzan_sync_issue SET detail_masked='新的重试仍失败 13900139000',"
                + "attempts=attempts+1 WHERE tenant_id=1 AND id=?", issueId);
        JsonNode afterRetry = data(get(filter).cookie(admin.cookies()));
        assertThat(afterRetry.at("/total").asInt()).isEqualTo(1);
        assertThat(afterRetry.at("/items/0/id").asLong()).isEqualTo(riskId);
        assertThat(afterRetry.at("/items/0/status").asText()).isEqualTo("CLOSED");
        assertThat(queryInt("SELECT COUNT(*) FROM crm_risk_case_history WHERE tenant_id=1 AND risk_case_id=?", riskId))
                .isEqualTo(4);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_risk_case WHERE tenant_id=1 AND source_type='YOUZAN_SYNC_ISSUE' "
                + "AND source_ref=? AND risk_type='PAYMENT_MISMATCH'", Long.toString(issueId))).isEqualTo(1);

        long newIssueId = newYouzanIssue("OPEN", "新的真实支付异常");
        JsonNode newCase = data(get("/api/v1/risks?sourceType=YOUZAN_SYNC_ISSUE&sourceRef=" + newIssueId)
                .cookie(admin.cookies()));
        assertThat(newCase.at("/total").asInt()).isEqualTo(1);
        assertThat(newCase.at("/items/0/id").asLong()).isNotEqualTo(riskId);
        assertThat(newCase.at("/items/0/status").asText()).isEqualTo("OPEN");
    }

    @Test
    void reconciliationImportsRealPaymentDifferenceWithoutRepairingOrderFacts() throws Exception {
        String suffix = suffix();
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,customer_no,display_name) "
                + "VALUES (1,1,?,'合成风控客户')", "IT-RISK-CUSTOMER-" + suffix);
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                "IT-RISK-CUSTOMER-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "source_system,order_type_code,status,receiver_type,payable_fen,paid_fen,refunded_fen) "
                        + "VALUES (1,?,?,1,1,'CRM','REGULAR','PAID','PLATFORM',1000,1000,0)",
                "IT-RISK-ORDER-" + suffix, customerId);
        long orderId = queryLong("SELECT id FROM crm_order WHERE tenant_id=1 AND order_no=?", "IT-RISK-ORDER-" + suffix);

        JsonNode result = data(get("/api/v1/risks").param("sourceType", "ORDER")
                .param("sourceRef", Long.toString(orderId)).param("riskType", "ORDER_PAYMENT_CASH")
                .cookie(admin.cookies()));

        assertThat(result.at("/total").asInt()).isEqualTo(1);
        assertThat(result.at("/items/0/evidence").asText()).contains("\"expectedFen\":1000", "\"actualFen\":0");
        assertThat(result.at("/items/0/status").asText()).isEqualTo("OPEN");
        assertThat(result.at("/items/0/orderId").asLong()).isEqualTo(orderId);
        assertThat(result.at("/items/0/orderNo").asText()).isEqualTo("IT-RISK-ORDER-" + suffix);
        assertThat(result.at("/items/0/customerId").asLong()).isEqualTo(customerId);
        assertThat(result.at("/items/0/customerNo").asText()).isEqualTo("IT-RISK-CUSTOMER-" + suffix);
        assertThat(result.at("/items/0/customerName").asText()).isEqualTo("合成风控客户");
        assertThat(queryLong("SELECT paid_fen FROM crm_order WHERE tenant_id=1 AND id=?", orderId)).isEqualTo(1000);
        assertThat(queryLong("SELECT version FROM crm_order WHERE tenant_id=1 AND id=?", orderId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_payment_txn WHERE tenant_id=1 AND order_id=?", orderId)).isZero();
    }

    @Test
    void youzanIssueResolvesOrderAndCustomerThroughRealEventMapping() throws Exception {
        String suffix = suffix();
        long customerId = newCustomer(1, "IT-RISK-YZ-CUSTOMER-" + suffix, "合成有赞订单客户");
        long orderId = newOrder(1, "IT-RISK-YZ-ORDER-" + suffix, customerId);
        long issueId = newYouzanIssue("OPEN", "合成有赞订单异常");
        long eventId = queryLong("SELECT event_id FROM crm_youzan_sync_issue WHERE tenant_id=1 AND id=?", issueId);
        jdbcTemplate.update("INSERT INTO crm_youzan_order_map "
                        + "(tenant_id,shop_id,external_order_no,crm_order_id,last_external_updated_at) "
                        + "SELECT 1,shop_id,external_order_no,?,CURRENT_TIMESTAMP(3) FROM crm_youzan_event "
                        + "WHERE tenant_id=1 AND id=?", orderId, eventId);
        jdbcTemplate.update("UPDATE crm_youzan_event SET order_id=? WHERE tenant_id=1 AND id=?", orderId, eventId);

        JsonNode result = data(get("/api/v1/risks").param("sourceType", "YOUZAN_SYNC_ISSUE")
                .param("sourceRef", Long.toString(issueId)).param("riskType", "PAYMENT_MISMATCH")
                .cookie(admin.cookies()));

        assertThat(result.at("/total").asInt()).isEqualTo(1);
        assertThat(result.at("/items/0/orderId").asLong()).isEqualTo(orderId);
        assertThat(result.at("/items/0/orderNo").asText()).isEqualTo("IT-RISK-YZ-ORDER-" + suffix);
        assertThat(result.at("/items/0/customerId").asLong()).isEqualTo(customerId);
        assertThat(result.at("/items/0/customerNo").asText()).isEqualTo("IT-RISK-YZ-CUSTOMER-" + suffix);
        assertThat(result.at("/items/0/customerName").asText()).isEqualTo("合成有赞订单客户");
    }

    @Test
    void youzanIssueWithoutOrderUsesOnlyConfirmedCustomerMapping() throws Exception {
        String suffix = suffix();
        long issueId = newYouzanIssue("OPEN", "合成有赞客户待匹配异常");
        long eventId = queryLong("SELECT event_id FROM crm_youzan_sync_issue WHERE tenant_id=1 AND id=?", issueId);
        String filter = "/api/v1/risks?sourceType=YOUZAN_SYNC_ISSUE&sourceRef=" + issueId
                + "&riskType=PAYMENT_MISMATCH";

        JsonNode beforeLink = data(get(filter).cookie(admin.cookies()));
        assertThat(beforeLink.at("/items/0/customerId").isMissingNode()).isTrue();

        long customerId = newCustomer(1, "IT-RISK-LINK-CUSTOMER-" + suffix, "合成确认映射客户");
        String externalCustomerId = "IT-RISK-EXTERNAL-CUSTOMER-" + suffix;
        String shopId = queryString("SELECT shop_id FROM crm_youzan_event WHERE tenant_id=1 AND id=?", eventId);
        jdbcTemplate.update("UPDATE crm_youzan_event SET payload_masked_json=? WHERE tenant_id=1 AND id=?",
                json(Map.of("external_customer_id", externalCustomerId)), eventId);
        jdbcTemplate.update("INSERT INTO crm_youzan_customer_link "
                        + "(tenant_id,shop_id,external_customer_id,customer_id,link_type,confidence,created_by) "
                        + "VALUES (1,?, ?,?,'MANUAL','HIGH',1)", shopId, externalCustomerId, customerId);

        JsonNode afterLink = data(get(filter).cookie(admin.cookies()));
        assertThat(afterLink.at("/items/0/orderId").isMissingNode()).isTrue();
        assertThat(afterLink.at("/items/0/customerId").asLong()).isEqualTo(customerId);
        assertThat(afterLink.at("/items/0/customerNo").asText()).isEqualTo("IT-RISK-LINK-CUSTOMER-" + suffix);
        assertThat(afterLink.at("/items/0/customerName").asText()).isEqualTo("合成确认映射客户");
    }

    @Test
    void customerAssociationCannotCrossTenantBoundary() throws Exception {
        String suffix = suffix();
        long otherCustomerId = newCustomer(2, "IT-RISK-OTHER-CUSTOMER-" + suffix, "合成其他租户客户");
        long otherOrderId = newOrder(2, "IT-RISK-OTHER-ORDER-" + suffix, otherCustomerId);
        long ownRiskId = newRisk(1, "IT-RISK-CROSS-TENANT-" + suffix, "SECURITY", "ORDER",
                Long.toString(otherOrderId), "OPEN", "合成跨租户关联校验");

        JsonNode detail = data(get("/api/v1/risks/" + ownRiskId).cookie(admin.cookies()));

        assertThat(detail.at("/riskCase/id").asLong()).isEqualTo(ownRiskId);
        assertThat(detail.at("/riskCase/orderId").isMissingNode()).isTrue();
        assertThat(detail.at("/riskCase/customerId").isMissingNode()).isTrue();
        assertThat(detail.at("/riskCase/customerName").isMissingNode()).isTrue();
    }

    private AuthCookies newRiskActor(String userType, String baseRole) throws Exception {
        String suffix = suffix();
        String username = "it-risk-actor-" + suffix;
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) "
                        + "VALUES (1,1,NULL,?,?,'$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri',?,'ACTIVE')",
                username, "合成风控越权账号", userType);
        long userId = queryLong("SELECT id FROM sys_user WHERE tenant_id=1 AND username=?", username);
        String roleCode = "IT_RISK_" + suffix.toUpperCase();
        jdbcTemplate.update("INSERT INTO sys_role (tenant_id,role_code,role_name,status) VALUES (1,?,'合成风控权限角色','ACTIVE')",
                roleCode);
        long roleId = queryLong("SELECT id FROM sys_role WHERE tenant_id=1 AND role_code=?", roleCode);
        long baseRoleId = queryLong("SELECT id FROM sys_role WHERE tenant_id=1 AND role_code=?", baseRole);
        jdbcTemplate.update("INSERT INTO sys_role_permission (tenant_id,role_id,permission_id) "
                + "SELECT 1,?,id FROM sys_permission WHERE tenant_id=1 AND permission_code IN ('risk:read','risk:write')", roleId);
        jdbcTemplate.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (1,?,?),(1,?,?)",
                userId, roleId, userId, baseRoleId);
        return login(username);
    }

    private long newCustomer(long tenantId, String customerNo, String displayName) {
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,customer_no,display_name) "
                + "VALUES (?,1,?,?)", tenantId, customerNo, displayName);
        return queryLong("SELECT id FROM crm_customer WHERE tenant_id=? AND customer_no=?", tenantId, customerNo);
    }

    private long newOrder(long tenantId, String orderNo, long customerId) {
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "source_system,order_type_code,status,receiver_type,payable_fen,paid_fen,refunded_fen) "
                        + "VALUES (?,?,?,1,1,'CRM','REGULAR','PAID','PLATFORM',1000,1000,0)",
                tenantId, orderNo, customerId);
        return queryLong("SELECT id FROM crm_order WHERE tenant_id=? AND order_no=?", tenantId, orderNo);
    }

    private long newRisk(long tenantId, String caseNo, String riskType, String sourceType, String sourceRef,
                         String status, String evidence) {
        jdbcTemplate.update("INSERT INTO crm_risk_case (tenant_id,risk_case_no,risk_type,severity,source_type,source_ref,"
                        + "status,evidence) VALUES (?,?,?,'HIGH',?,?,?,?)",
                tenantId, caseNo, riskType, sourceType, sourceRef, status, evidence);
        long id = queryLong("SELECT id FROM crm_risk_case WHERE tenant_id=? AND risk_case_no=?", tenantId, caseNo);
        jdbcTemplate.update("INSERT INTO crm_risk_case_history (tenant_id,risk_case_id,action,from_status,to_status,note) "
                + "VALUES (?,?,'REGISTER',NULL,?,'合成测试登记')", tenantId, id, status);
        return id;
    }

    private long newYouzanIssue(String issueStatus, String detail) {
        String suffix = suffix();
        String shopId = "IT-RISK-SHOP-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_youzan_event (tenant_id,shop_id,event_id,event_type,external_order_no,"
                        + "payload_masked_json,payload_cipher,signature_valid,source_system,process_status,process_attempts,received_at) "
                        + "VALUES (1,?,?,'ORDER_PAID',?,'{}','synthetic-cipher',1,'WEBHOOK','PAYMENT_MISMATCH',1,CURRENT_TIMESTAMP(3))",
                shopId, "IT-RISK-EVENT-" + suffix, "IT-RISK-EXTERNAL-" + suffix);
        long eventId = queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? AND event_id=?",
                shopId, "IT-RISK-EVENT-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_youzan_sync_issue (tenant_id,event_id,issue_type,status,detail_masked,attempts) "
                + "VALUES (1,?,'PAYMENT_MISMATCH',?,?,1)", eventId, issueStatus, detail);
        return queryLong("SELECT id FROM crm_youzan_sync_issue WHERE tenant_id=1 AND event_id=? "
                + "AND issue_type='PAYMENT_MISMATCH'", eventId);
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key() {
        return "it-risk-" + UUID.randomUUID();
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(value).isNotNull();
        return value;
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }
}
