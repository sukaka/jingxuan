package com.whale.crm.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.trading.domain.TradingRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.doAnswer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerMergeReversalApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoSpyBean
    private TradingRepository tradingRepository;

    @Test
    void mergeRejectsExistingAppointmentWithoutChangingCustomers() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成预约主");
        long secondary = createCustomer(admin, "合成预约从");
        long order = orderWithEntitlement(secondary, "appointment-block");
        appointment(secondary, entitlementOf(order));
        long caseId = createCase(admin, primary, secondary);
        data(admin, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"reason\":\"测试\"}"))
                .andExpect(status().isConflict());
        assertThat(ownerOf("crm_order", order)).isEqualTo(secondary);
        assertThat(ownerOf("crm_entitlement", entitlementOf(order))).isEqualTo(secondary);
        assertThat(mergeStatus(caseId)).isEqualTo("REVIEWING");
        assertThat(customerDeleted(secondary)).isZero();
        assertThat(count("crm_merge_resource_snapshot", caseId)).isZero();
    }

    @Test
    void mergeSeesAppointmentCommittedWhileWaitingForEntitlementLock() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成并发主");
        long secondary = createCustomer(admin, "合成并发从");
        long order = orderWithEntitlement(secondary, "appointment-race");
        long entitlement = entitlementOf(order);
        long caseId = createCase(admin, primary, secondary);
        data(admin, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (invocation.getArgument(1).equals(secondary)) started.countDown();
            return invocation.callRealMethod();
        }).when(tradingRepository).findEntitlementIdsByCustomer(1L, secondary);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM crm_entitlement WHERE tenant_id=1 AND id=? FOR UPDATE", Long.class, entitlement);
                var result = executor.submit(() -> {
                    return mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"reason\":\"测试\"}")).andReturn();
                });
                try {
                    if (!started.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("合并未启动");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                appointment(secondary, entitlement);
                return result;
            });
            assertThat(future.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(ownerOf("crm_entitlement", entitlement)).isEqualTo(secondary);
        assertThat(customerDeleted(secondary)).isZero();
    }

    @Test
    void mergeRejectsExistingRefundWithoutChangingCustomers() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成退款主");
        long secondary = createCustomer(admin, "合成退款从");
        long order = orderWithEntitlement(secondary, "refund-block");
        refund(order, secondary);
        long caseId = createCase(admin, primary, secondary);
        data(admin, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"reason\":\"测试\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(ownerOf("crm_order", order)).isEqualTo(secondary);
        assertThat(ownerOf("crm_entitlement", entitlementOf(order))).isEqualTo(secondary);
        assertThat(customerDeleted(secondary)).isZero();
        assertThat(mergeStatus(caseId)).isEqualTo("REVIEWING");
        assertThat(count("crm_merge_resource_snapshot", caseId)).isZero();
    }

    @Test
    void reversalRejectsRefundCreatedAfterMerge() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成撤销退款主");
        long secondary = createCustomer(admin, "合成撤销退款从");
        long order = orderWithEntitlement(secondary, "reversal-refund-block");
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        refund(order, primary);
        long reversalId = requestReversal(admin, caseId, "it-refund-reversal-request", "申请撤销");
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                        .header("Idempotency-Key", "it-refund-reversal-approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"测试\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(ownerOf("crm_order", order)).isEqualTo(primary);
        assertThat(customerDeleted(secondary)).isEqualTo(1);
        assertThat(mergeStatus(caseId)).isEqualTo("CONFIRMED");
        assertThat(reversalStatus(reversalId)).isEqualTo("REQUESTED");
    }

    @Test
    void reversalRejectsAppointmentCreatedAfterMerge() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成撤销预约主");
        long secondary = createCustomer(admin, "合成撤销预约从");
        long order = orderWithEntitlement(secondary, "reversal-appointment-block");
        long entitlement = entitlementOf(order);
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        int snapshots = count("crm_merge_resource_snapshot", caseId);
        appointment(primary, entitlement);
        long reversalId = requestReversal(admin, caseId, "it-appointment-reversal-request", "申请撤销");
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                        .header("Idempotency-Key", "it-appointment-reversal-approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"测试\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(ownerOf("crm_order", order)).isEqualTo(primary);
        assertThat(ownerOf("crm_entitlement", entitlement)).isEqualTo(primary);
        assertThat(customerDeleted(secondary)).isEqualTo(1);
        assertThat(mergeStatus(caseId)).isEqualTo("CONFIRMED");
        assertThat(reversalStatus(reversalId)).isEqualTo("REQUESTED");
        assertThat(count("crm_merge_resource_snapshot", caseId)).isEqualTo(snapshots);
    }

    private void refund(long order, long customer) {
        jdbcTemplate.update("INSERT INTO crm_refund (tenant_id,refund_no,order_id,customer_id,requested_fen,reason_code,status,requested_by) VALUES (1,?,?,?,100,'TEST','REQUESTED',1)",
                "IT-REFUND-" + java.util.UUID.randomUUID(), order, customer);
    }

    private void appointment(long customer, long entitlement) {
        String no = "IT-APPT-" + java.util.UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO crm_appointment (tenant_id,appointment_no,customer_id,institution_id,owner_staff_id,scheduled_at,status,created_by) VALUES (1,?,?,1,2,NOW(),'PENDING_INSTITUTION',1)", no, customer);
        long appointment = jdbcTemplate.queryForObject("SELECT id FROM crm_appointment WHERE appointment_no=?", Long.class, no);
        jdbcTemplate.update("INSERT INTO crm_appointment_item (tenant_id,appointment_id,entitlement_id,project_id,planned_count) SELECT 1,?,id,project_id,1 FROM crm_entitlement WHERE id=?", appointment, entitlement);
    }

    @Test
    void snapshotReversalOnlyRestoresMergedResourcesAndPreservesNewFacts() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成更正主");
        long secondary = createCustomer(admin, "合成更正从");
        long primaryIdentity = identity(primary, "primary-original-reversal");
        long secondaryIdentity = identity(secondary, "unique-reversal");
        tags(admin, primary, "VIP");
        tags(admin, secondary, "VIP", "RISK");
        adjustPoints(admin, primary, 5);
        adjustPoints(admin, secondary, 7);
        long secondaryPoints = pointsId(secondary);
        long followup = createFollowup(admin, secondary);
        long task = createTask(admin, secondary);
        long primaryOrder = orderWithEntitlement(primary, "primary-before");
        long secondaryOrder = orderWithEntitlement(secondary, "secondary-before");

        long caseId = createCase(admin, primary, secondary);
        reversalRequest(admin, caseId, "not-confirmed", "尚未合并").andExpect(status().isConflict());
        confirmCase(admin, caseId);

        long postMergeIdentity = identity(primary, "post-merge-reversal");
        long postMergeFollowup = createFollowup(admin, primary);
        jdbcTemplate.update("INSERT INTO crm_customer_tag (tenant_id,customer_id,tag_code,created_by) VALUES (1,?,'POST_MERGE_TEST',1)", primary);
        long postMergeOrder = orderWithEntitlement(primary, "primary-after");
        refund(primaryOrder, primary);
        assertThat(snapshotCount(caseId, "IDENTITY", primaryIdentity)).isZero();
        assertThat(snapshotCount(caseId, "IDENTITY", secondaryIdentity)).isEqualTo(1);
        assertThat(snapshotCount(caseId, "ORDER", primaryOrder)).isZero();
        assertThat(snapshotCount(caseId, "ORDER", secondaryOrder)).isEqualTo(1);
        assertThat(snapshotCount(caseId, "POINTS", pointsId(primary))).isZero();
        assertThat(snapshotCount(caseId, "POINTS", secondaryPoints)).isEqualTo(1);
        assertThat(count("crm_merge_resource_snapshot", caseId)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_merge_resource_snapshot WHERE merge_case_id=? AND resource_type='TAG' AND resource_key='VIP'", Integer.class, caseId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_merge_resource_snapshot WHERE merge_case_id=? AND resource_type='TAG' AND resource_key='RISK'", Integer.class, caseId)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/customer-merge-candidates/" + caseId + "/reversal-requests")
                        .cookie(admin.cookies()).header("Idempotency-Key", "it-reversal-no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"缺少CSRF\"}"))
                .andExpect(status().isForbidden());
        long reversalId = requestReversal(admin, caseId, "it-reversal-request-1", "误合并，申请恢复");
        JsonNode replay = data(admin, post("/api/v1/customer-merge-candidates/" + caseId + "/reversal-requests")
                .header("Idempotency-Key", "it-reversal-request-1"), "{\"reason\":\"误合并，申请恢复\"}", 200);
        assertThat(replay.at("/id").asLong()).isEqualTo(reversalId);
        reversalRequest(admin, caseId, "it-reversal-request-duplicate", "重复申请").andExpect(status().isConflict());
        mockMvc.perform(login("lead1").withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                        .header("Idempotency-Key", "it-reversal-no-permission")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"无权限审批\"}"))
                .andExpect(status().isForbidden());
        assertThat(customerDeleted(secondary)).isEqualTo(1);

        String approveBody = "{\"version\":0,\"reason\":\"核实误合并，同意按快照恢复\"}";
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                        .header("Idempotency-Key", "it-reversal-approve-1")
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.warningMessage").value(org.hamcrest.Matchers.containsString("去重删除")));
        data(admin, post("/api/v1/customer-merge-reversals/" + reversalId + "/approve")
                .header("Idempotency-Key", "it-reversal-approve-1"), approveBody, 200);

        assertThat(customerDeleted(secondary)).isZero();
        assertThat(ownerOf("crm_customer_identity", secondaryIdentity)).isEqualTo(secondary);
        assertThat(ownerOf("crm_customer_identity", postMergeIdentity)).isEqualTo(primary);
        assertThat(countByExternalId("primary-original-reversal")).isEqualTo(1);
        assertThat(ownerOf("crm_customer_identity", primaryIdentity)).isEqualTo(primary);
        assertThat(tagCount(primary, "VIP")).isEqualTo(1);
        assertThat(tagCount(primary, "POST_MERGE_TEST")).isEqualTo(1);
        assertThat(tagCount(secondary, "RISK")).isEqualTo(1);
        assertThat(ownerOf("crm_followup", followup)).isEqualTo(secondary);
        assertThat(ownerOf("crm_followup", postMergeFollowup)).isEqualTo(primary);
        assertThat(ownerOf("crm_task", task)).isEqualTo(secondary);
        assertThat(pointsBalance(primary)).isEqualTo(5);
        assertThat(pointsBalance(secondary)).isEqualTo(7);
        assertThat(ownerOf("crm_order", primaryOrder)).isEqualTo(primary);
        assertThat(ownerOf("crm_order", secondaryOrder)).isEqualTo(secondary);
        assertThat(ownerOf("crm_order", postMergeOrder)).isEqualTo(primary);
        assertThat(ownerOf("crm_entitlement", entitlementOf(primaryOrder))).isEqualTo(primary);
        assertThat(ownerOf("crm_entitlement", entitlementOf(secondaryOrder))).isEqualTo(secondary);
        assertThat(ownerOf("crm_entitlement", entitlementOf(postMergeOrder))).isEqualTo(primary);
        assertThat(jdbcTemplate.queryForObject("SELECT paid_fen FROM crm_order WHERE id=?", Long.class, secondaryOrder)).isEqualTo(1200L);
        assertThat(mergeStatus(caseId)).isEqualTo("REVERSED");
        assertThat(jdbcTemplate.queryForObject("SELECT after_json FROM crm_audit_log WHERE action_code='MERGE_REVERSAL_APPROVE' AND target_id=?", String.class, Long.toString(reversalId)))
                .contains("EXECUTED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log WHERE action_code='MERGE_REVERSAL_APPROVE' AND target_id=?", Integer.class, Long.toString(reversalId))).isEqualTo(1);
    }

    @Test
    void legacyReversalRequiresManualCompletionWithoutMovingUnknownResources() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成旧合并主");
        long secondary = createCustomer(admin, "合成旧合并从");
        long followup = createFollowup(admin, secondary);
        adjustPoints(admin, secondary, 7);
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        jdbcTemplate.update("DELETE FROM crm_merge_resource_snapshot WHERE tenant_id=1 AND merge_case_id=?", caseId);

        long reversalId = requestReversal(admin, caseId, "it-reversal-legacy-request", "历史合并信息不全");
        data(admin, post("/api/v1/customer-merge-reversals/" + reversalId + "/approve")
                .header("Idempotency-Key", "it-reversal-legacy-approve"), "{\"version\":0,\"reason\":\"先恢复客户，人工核对资源\"}", 200);
        assertThat(customerDeleted(secondary)).isZero();
        assertThat(ownerOf("crm_followup", followup)).isEqualTo(primary);
        assertThat(pointsBalance(primary)).isEqualTo(7);
        assertThat(pointsBalance(secondary)).isEqualTo(7);
        assertThat(mergeStatus(caseId)).isEqualTo("CONFIRMED");
        assertThat(reversalStatus(reversalId)).isEqualTo("MANUAL_REVIEW");

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/complete"))
                        .header("Idempotency-Key", "it-reversal-legacy-stale")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"旧版本\"}"))
                .andExpect(status().isConflict());
        data(admin, post("/api/v1/customer-merge-reversals/" + reversalId + "/complete")
                        .header("Idempotency-Key", "it-reversal-legacy-complete"),
                "{\"version\":1,\"reason\":\"已逐项核对身份、标签、跟进、任务、积分、订单和权益\"}", 200);
        assertThat(reversalStatus(reversalId)).isEqualTo("EXECUTED");
        assertThat(mergeStatus(caseId)).isEqualTo("REVERSED");
    }

    @Test
    void rejectAndOutOfScopeReviewDoNotChangeCustomers() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成驳回主", 3);
        long secondary = createCustomer(admin, "合成驳回从", 3);
        long followup = createFollowup(admin, secondary);
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        long reversalId = requestReversal(admin, caseId, "it-reversal-reject-request", "申请后仍需核实");

        jdbcTemplate.update("INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id) "
                + "SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id "
                + "WHERE r.role_code='MANAGER_LEAD' AND p.permission_code='customer:merge_reverse'");
        try {
            AuthCookies lead = login("lead1");
            mockMvc.perform(lead.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                            .header("Idempotency-Key", "it-reversal-scope-approve")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"跨组审批\"}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(lead.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/reject"))
                            .header("Idempotency-Key", "it-reversal-scope-reject")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"跨组驳回\"}"))
                    .andExpect(status().isNotFound());
        } finally {
            jdbcTemplate.update("DELETE rp FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id "
                    + "JOIN sys_permission p ON p.id=rp.permission_id "
                    + "WHERE r.role_code='MANAGER_LEAD' AND p.permission_code='customer:merge_reverse'");
        }
        data(admin, post("/api/v1/customer-merge-reversals/" + reversalId + "/reject")
                .header("Idempotency-Key", "it-reversal-reject"), "{\"version\":0,\"reason\":\"核实后不执行更正\"}", 200);
        assertThat(reversalStatus(reversalId)).isEqualTo("REJECTED");
        assertThat(mergeStatus(caseId)).isEqualTo("CONFIRMED");
        assertThat(customerDeleted(secondary)).isEqualTo(1);
        assertThat(ownerOf("crm_followup", followup)).isEqualTo(primary);
    }

    @Test
    void failedMigrationRollsBackRestoreAndWritesMaskedErrorAudit() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成回滚主");
        long secondary = createCustomer(admin, "合成回滚从");
        long followup = createFollowup(admin, secondary);
        tags(admin, primary, "VIP");
        tags(admin, secondary, "VIP", "RISK");
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        long reversalId = requestReversal(admin, caseId, "it-reversal-failure-request", "验证失败回滚");
        jdbcTemplate.update("UPDATE crm_merge_resource_snapshot SET resource_key='INVALID' WHERE merge_case_id=? AND resource_type='FOLLOWUP'", caseId);

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                        .header("Idempotency-Key", "it-reversal-failure-approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"验证异常回滚\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        assertThat(reversalStatus(reversalId)).isEqualTo("ERROR");
        assertThat(customerDeleted(secondary)).isEqualTo(1);
        assertThat(ownerOf("crm_followup", followup)).isEqualTo(primary);
        assertThat(mergeStatus(caseId)).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("SELECT error_masked FROM crm_merge_reversal WHERE id=?", String.class, reversalId))
                .doesNotContain("SQL", "VIP", "Duplicate");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log WHERE action_code='MERGE_REVERSAL_ERROR' AND target_id=?", Integer.class, Long.toString(reversalId))).isEqualTo(1);
    }

    private long createCustomer(AuthCookies actor, String nickname) throws Exception {
        return createCustomer(actor, nickname, 2);
    }

    private long createCustomer(AuthCookies actor, String nickname, long ownerId) throws Exception {
        return data(actor, post("/api/v1/customers"), "{\"nickname\":\"" + nickname + "\",\"ownerStaffId\":" + ownerId + "}", 201).at("/id").asLong();
    }

    private long createCase(AuthCookies actor, long primary, long secondary) throws Exception {
        return data(actor, post("/api/v1/customer-merges"), "{\"primaryCustomerId\":" + primary + ",\"secondaryCustomerId\":" + secondary + ",\"reason\":\"合成测试候选\"}", 201).at("/id").asLong();
    }

    private void confirmCase(AuthCookies actor, long caseId) throws Exception {
        data(actor, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);
        data(actor, post("/api/v1/customer-merges/" + caseId + "/confirm"), "{\"version\":1,\"reason\":\"合成测试确认合并\"}", 200);
    }

    private org.springframework.test.web.servlet.ResultActions reversalRequest(AuthCookies actor, long caseId, String key, String reason) throws Exception {
        return mockMvc.perform(actor.withAuth(post("/api/v1/customer-merge-candidates/" + caseId + "/reversal-requests"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("reason", reason))));
    }

    private long requestReversal(AuthCookies actor, long caseId, String key, String reason) throws Exception {
        return reversalRequest(actor, caseId, key, reason).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString().transform(body -> {
                    try {
                        return objectMapper.readTree(body).at("/data/id").asLong();
                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                });
    }

    private JsonNode data(AuthCookies actor, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                          String body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(actor.withAuth(builder).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private long identity(long customerId, String externalId) {
        jdbcTemplate.update("INSERT INTO crm_customer_identity (tenant_id,customer_id,identity_type,source_app,external_id,status) VALUES (1,?,'WECOM','it-reversal',?,'LINKED')", customerId, externalId);
        return jdbcTemplate.queryForObject("SELECT id FROM crm_customer_identity WHERE external_id=?", Long.class, externalId);
    }

    private void tags(AuthCookies actor, long customerId, String... codes) throws Exception {
        data(actor, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/customers/" + customerId + "/tags"),
                objectMapper.writeValueAsString(java.util.Map.of("tagCodes", codes)), 200);
    }

    private void adjustPoints(AuthCookies actor, long customerId, int delta) throws Exception {
        mockMvc.perform(actor.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .header("Idempotency-Key", "it-reversal-points-" + customerId + "-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":" + delta + ",\"reasonCode\":\"TEST\"}"))
                .andExpect(status().isOk());
    }

    private long createFollowup(AuthCookies actor, long customerId) throws Exception {
        return data(actor, post("/api/v1/followups"), "{\"customerId\":" + customerId + ",\"channelCode\":\"WECOM\",\"summary\":\"合成更正测试\"}", 201).at("/id").asLong();
    }

    private long createTask(AuthCookies actor, long customerId) throws Exception {
        return data(actor, post("/api/v1/tasks"), "{\"customerId\":" + customerId + ",\"taskType\":\"FOLLOWUP\",\"title\":\"合成更正测试\"}", 201).at("/id").asLong();
    }

    private long orderWithEntitlement(long customerId, String suffix) {
        String orderNo = "IT-REVERSAL-" + suffix + "-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,source_system,order_type_code,status,receiver_type,payable_fen,paid_fen,refunded_fen) VALUES (1,?,?,2,2,'CRM','REGULAR','PAID','PLATFORM',1200,1200,0)", orderNo, customerId);
        long orderId = jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE order_no=?", Long.class, orderNo);
        jdbcTemplate.update("INSERT INTO crm_order_item (tenant_id,order_id,sku_id,package_version_id,sku_name_snapshot,purchase_quantity,paid_allocated_fen) SELECT 1,?,id,package_version_id,'合成订单快照',1,1200 FROM crm_sku WHERE id=301", orderId);
        long itemId = jdbcTemplate.queryForObject("SELECT id FROM crm_order_item WHERE order_id=?", Long.class, orderId);
        jdbcTemplate.update("INSERT INTO crm_entitlement (tenant_id,customer_id,order_item_id,project_id,granted_count,available_count,version_no,status) SELECT 1,?,?,project_id,2,2,1,'AVAILABLE' FROM crm_package_project WHERE package_version_id=(SELECT package_version_id FROM crm_sku WHERE id=301) ORDER BY project_id LIMIT 1", customerId, itemId);
        return orderId;
    }

    private long snapshotCount(long caseId, String type, long resourceId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_merge_resource_snapshot WHERE merge_case_id=? AND resource_type=? AND resource_key=?", Long.class, caseId, type, Long.toString(resourceId));
    }

    private int count(String table, long caseId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE merge_case_id=?", Integer.class, caseId);
    }

    private long countByExternalId(String externalId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_identity WHERE external_id=?", Long.class, externalId);
    }

    private long ownerOf(String table, long id) {
        return jdbcTemplate.queryForObject("SELECT customer_id FROM " + table + " WHERE id=?", Long.class, id);
    }

    private long tagCount(long customerId, String code) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tag WHERE customer_id=? AND tag_code=?", Long.class, customerId, code);
    }

    private long pointsId(long customerId) {
        return jdbcTemplate.queryForObject("SELECT id FROM crm_points_ledger WHERE customer_id=? ORDER BY id LIMIT 1", Long.class, customerId);
    }

    private long entitlementOf(long orderId) {
        return jdbcTemplate.queryForObject("SELECT e.id FROM crm_entitlement e JOIN crm_order_item i ON i.id=e.order_item_id WHERE i.order_id=?", Long.class, orderId);
    }

    private int customerDeleted(long customerId) {
        return jdbcTemplate.queryForObject("SELECT deleted FROM crm_customer WHERE id=?", Integer.class, customerId);
    }

    private int pointsBalance(long customerId) {
        return jdbcTemplate.queryForObject("SELECT points_cached FROM crm_customer WHERE id=?", Integer.class, customerId);
    }

    private String mergeStatus(long caseId) {
        return jdbcTemplate.queryForObject("SELECT status FROM crm_merge_case WHERE id=?", String.class, caseId);
    }

    private String reversalStatus(long reversalId) {
        return jdbcTemplate.queryForObject("SELECT status FROM crm_merge_reversal WHERE id=?", String.class, reversalId);
    }
}
