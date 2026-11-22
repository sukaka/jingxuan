package com.whale.crm.settlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettlementApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthCookies finance;
    private AuthCookies institutionFinance;
    private AuthCookies limitedFinance;
    private long institutionFinanceId;

    @BeforeAll
    void actors() throws Exception {
        finance = login("finance1");
        String username = "settlement-finance-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) "
                        + "VALUES (1,1,NULL,?,?,'$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','INSTITUTION','ACTIVE')",
                username, "合成机构财务");
        institutionFinanceId = queryLong("SELECT id FROM sys_user WHERE tenant_id=1 AND username=?", username);
        jdbcTemplate.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (1,?,8)", institutionFinanceId);
        institutionFinance = login(username);

        String limitedUsername = "settlement-limited-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) "
                        + "VALUES (1,1,NULL,?,?,'$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','EMPLOYEE','ACTIVE')",
                limitedUsername, "有限范围财务");
        long limitedId = queryLong("SELECT id FROM sys_user WHERE tenant_id=1 AND username=?", limitedUsername);
        jdbcTemplate.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (1,?,6)", limitedId);
        jdbcTemplate.update("INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type) VALUES (1,?,'institution','SELF')",
                limitedId);
        limitedFinance = login(limitedUsername);
    }

    @Test
    void postedRedemptionCreatesPendingMonthlyBatchWithRateSnapshotAndUniqueLine() throws Exception {
        long institutionId = newInstitution("MONTHLY");
        jdbcTemplate.update("UPDATE crm_institution SET settlement_day='8' WHERE tenant_id=1 AND id=?", institutionId);
        long redemptionId = newRedemption(institutionId, "POSTED", "2026-10-20T08:00:00Z");
        Map<String, Object> body = createBody(institutionId, "MONTHLY", "2026-10", List.of(redemptionId));
        String key = key("monthly");

        JsonNode created = data(finance.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(body)));
        long batchId = created.at("/batch/id").asLong();
        assertThat(created.at("/batch/status").asText()).isEqualTo("DRAFT");
        assertThat(created.at("/batch/amountStatus").asText()).isEqualTo("PENDING_VERIFICATION");
        assertThat(created.at("/batch/grossFen").asLong()).isZero();
        assertThat(created.at("/batch/netPayableFen").asLong()).isZero();
        assertThat(created.at("/batch/settlementDay").asText()).isEqualTo("8");
        assertThat(created.at("/batch/settlementDaySource").asText()).isEqualTo("INSTITUTION");
        assertThat(created.at("/batch/settlementDate").asText()).isEqualTo("2026-10-08");
        assertThat(created.at("/lines/0/redemptionId").asLong()).isEqualTo(redemptionId);
        String snapshot = queryString("SELECT rate_snapshot FROM crm_settlement_line WHERE tenant_id=1 AND settlement_batch_id=?", batchId);
        assertThat(objectMapper.readTree(snapshot).at("/onlineRate").asDouble()).isEqualTo(0.18);
        assertThat(objectMapper.readTree(snapshot).at("/institutionVersion").asLong()).isZero();

        jdbcTemplate.update("UPDATE crm_institution SET settlement_day='20' WHERE tenant_id=1 AND id=?", institutionId);
        JsonNode replay = data(finance.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(body)));
        assertThat(replay.at("/batch/id").asLong()).isEqualTo(batchId);
        assertThat(replay.at("/batch/settlementDate").asText()).isEqualTo("2026-10-08");
        assertThat(queryString("SELECT settlement_day FROM crm_settlement_batch WHERE tenant_id=1 AND id=?", batchId))
                .isEqualTo("8");
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_line WHERE tenant_id=1 AND redemption_id=?", redemptionId)).isEqualTo(1);

        String secondKey = key("monthly-existing");
        JsonNode existing = data(finance.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", secondKey).contentType(MediaType.APPLICATION_JSON).content(json(body)));
        assertThat(existing.at("/batch/id").asLong()).isEqualTo(batchId);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_idempotency_record WHERE tenant_id=1 "
                + "AND scope_code='settlement.batch.create' AND idempotency_key=?", secondKey)).isEqualTo(1);
        mockMvc.perform(finance.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", secondKey).contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody(institutionId, "MONTHLY", "2026-11", List.of(redemptionId)))))
                .andExpect(status().isConflict());

        jdbcTemplate.update("UPDATE crm_institution SET settlement_type='PER_REDEMPTION' WHERE tenant_id=1 AND id=?", institutionId);
        mockMvc.perform(finance.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", key("per-duplicate")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody(institutionId, "PER_REDEMPTION", "REDEMPTION:" + redemptionId, List.of(redemptionId)))))
                .andExpect(status().isConflict());
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_line WHERE tenant_id=1 AND redemption_id=?", redemptionId)).isEqualTo(1);

        long draftRedemption = newRedemption(institutionId, "REVERSED", "2026-10-21T08:00:00Z");
        mockMvc.perform(finance.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", key("not-posted")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody(institutionId, "PER_REDEMPTION", "REDEMPTION:" + draftRedemption,
                                List.of(draftRedemption)))))
                .andExpect(status().isConflict());
    }

    @Test
    void perRedemptionCreatesSingleBatchAndRejectsWrongPeriodOrInstitution() throws Exception {
        long institutionId = newInstitution("PER_REDEMPTION");
        jdbcTemplate.update("UPDATE crm_institution SET settlement_day='8' WHERE tenant_id=1 AND id=?", institutionId);
        long redemptionId = newRedemption(institutionId, "POSTED", "2026-10-20T08:00:00Z");
        JsonNode created = data(finance.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", key("per-success")).contentType(MediaType.APPLICATION_JSON)
                .content(json(createBody(institutionId, "PER_REDEMPTION", "REDEMPTION:" + redemptionId,
                        List.of(redemptionId)))));
        assertThat(created.at("/batch/settlementType").asText()).isEqualTo("PER_REDEMPTION");
        assertThat(created.at("/batch/periodKey").asText()).isEqualTo("REDEMPTION:" + redemptionId);
        assertThat(created.at("/lines")).hasSize(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_batch WHERE tenant_id=1 AND id=? "
                        + "AND settlement_day IS NULL AND settlement_day_source IS NULL AND settlement_date IS NULL",
                created.at("/batch/id").asLong())).isEqualTo(1);

        long otherRedemption = newRedemption(institutionId, "POSTED", "2026-10-21T08:00:00Z");
        mockMvc.perform(finance.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", key("wrong-period")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody(institutionId, "PER_REDEMPTION", "REDEMPTION:999999999",
                                List.of(otherRedemption)))))
                .andExpect(status().isBadRequest());
        long otherInstitution = newInstitution("PER_REDEMPTION");
        mockMvc.perform(finance.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", key("wrong-institution")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody(otherInstitution, "PER_REDEMPTION", "REDEMPTION:" + otherRedemption,
                                List.of(otherRedemption)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void manualVerificationAndCleanVoucherInstallmentsEnforceAllMoneyGuards() throws Exception {
        long institutionId = newInstitution("MONTHLY");
        long redemptionId = newRedemption(institutionId, "POSTED", "2026-10-20T08:00:00Z");
        long batchId = data(finance.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", key("payment-create")).contentType(MediaType.APPLICATION_JSON)
                .content(json(createBody(institutionId, "MONTHLY", "2026-10", List.of(redemptionId)))))
                .at("/batch/id").asLong();

        mockMvc.perform(payment(batchId, 0, 100, "PENDING-BANK", 9001, "pending-proof"))
                .andExpect(status().isConflict());
        mockMvc.perform(verification(batchId, 0, 1000, 101, 900))
                .andExpect(status().isBadRequest());
        JsonNode verified = data(verification(batchId, 0, 1000, 100, 900));
        assertThat(verified.at("/batch/status").asText()).isEqualTo("CONFIRMED");
        assertThat(verified.at("/batch/amountStatus").asText()).isEqualTo("VERIFIED");
        assertThat(verified.at("/batch/ruleVersion").asText()).isEqualTo("RULE-2026-10");

        JsonNode partial = data(payment(batchId, 1, 400, key("BANK-PART"), 9001, "manual-part"));
        assertThat(partial.at("/batch/status").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(partial.at("/batch/paidFen").asLong()).isEqualTo(400);
        assertThat(partial.at("/payments/0/voucherFileId").asLong()).isEqualTo(9001);
        mockMvc.perform(payment(batchId, 2, 100, key("BANK-DIRTY"), 9002, "dirty-file"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(payment(batchId, 2, 501, key("BANK-OVER"), 9001, "over-payment"))
                .andExpect(status().isConflict());
        JsonNode paid = data(payment(batchId, 2, 500, key("BANK-FINAL"), 9001, "manual-final"));
        assertThat(paid.at("/batch/status").asText()).isEqualTo("PAID");
        assertThat(paid.at("/batch/paidFen").asLong()).isEqualTo(900);
        assertThat(paid.at("/payments")).hasSize(2);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_payment WHERE tenant_id=1 AND settlement_batch_id=?", batchId))
                .isEqualTo(2);
    }

    @Test
    void institutionFinanceIsReadOnlyAndRestrictedToOwnInstitution() throws Exception {
        long ownInstitution = newInstitution("MONTHLY");
        long otherInstitution = newInstitution("MONTHLY");
        jdbcTemplate.update("INSERT INTO crm_institution_user (tenant_id,institution_id,user_id,institution_role,status) "
                + "VALUES (1,?,?,'OPERATOR','ACTIVE')", ownInstitution, institutionFinanceId);
        long ownBatch = newBatch(ownInstitution, newRedemption(ownInstitution, "POSTED", "2026-10-20T08:00:00Z"));
        long otherBatch = newBatch(otherInstitution, newRedemption(otherInstitution, "POSTED", "2026-10-20T08:00:00Z"));

        mockMvc.perform(get("/api/v1/settlements/batches/" + ownBatch).cookie(institutionFinance.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.batch.id").value(ownBatch));
        mockMvc.perform(get("/api/v1/settlements/batches/" + otherBatch).cookie(institutionFinance.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/settlements/batches?institutionId=" + otherInstitution)
                        .cookie(institutionFinance.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(institutionFinance.withAuth(post("/api/v1/settlements/batches/" + ownBatch + "/verification"))
                        .header("Idempotency-Key", key("institution-write")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(verifyBody(0, 1000, 100, 900))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/settlements/reconciliation?institutionId=" + otherInstitution)
                        .cookie(institutionFinance.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issues[?(@.institutionId == " + otherInstitution + ")]").isEmpty());
    }

    @Test
    void employeeWithoutInstitutionAllScopeCannotReadWriteOrReconcile() throws Exception {
        long institutionId = newInstitution("MONTHLY");
        long batchId = newBatch(institutionId, newRedemption(institutionId, "POSTED", "2026-10-20T08:00:00Z"));

        mockMvc.perform(get("/api/v1/settlements/batches").cookie(limitedFinance.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/settlements/batches/" + batchId).cookie(limitedFinance.cookies()))
                .andExpect(status().isNotFound());

        long createInstitution = newInstitution("MONTHLY");
        long createRedemption = newRedemption(createInstitution, "POSTED", "2026-10-20T08:00:00Z");
        mockMvc.perform(limitedFinance.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", key("limited-create")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody(createInstitution, "MONTHLY", "2026-10", List.of(createRedemption)))))
                .andExpect(status().isForbidden());
        mockMvc.perform(limitedFinance.withAuth(post("/api/v1/settlements/batches/" + batchId + "/verification"))
                        .header("Idempotency-Key", key("limited-verify")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(verifyBody(0, 1000, 100, 900))))
                .andExpect(status().isForbidden());
        mockMvc.perform(limitedFinance.withAuth(post("/api/v1/settlements/batches/" + batchId + "/payments"))
                        .header("Idempotency-Key", key("limited-payment")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "amountFen", 100, "bankTradeNo", key("LIMITED-BANK"),
                                "paidAt", Instant.now().toString(), "voucherFileId", 9001, "evidenceRef", "limited"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/settlements/reconciliation").cookie(limitedFinance.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.issueCount").value(0))
                .andExpect(jsonPath("$.data.issues").isEmpty());

        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_batch WHERE tenant_id=1 AND institution_id=?",
                createInstitution)).isZero();
        assertThat(queryString("SELECT amount_status FROM crm_settlement_batch WHERE tenant_id=1 AND id=?", batchId))
                .isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void reconciliationReportsDifferencesWithoutRepairingRows() throws Exception {
        long institutionId = newInstitution("MONTHLY");
        long batchedRedemption = newRedemption(institutionId, "POSTED", "2026-10-20T08:00:00Z");
        long batchId = newBatch(institutionId, batchedRedemption);
        long unbatchedRedemption = newRedemption(institutionId, "POSTED", "2026-10-21T08:00:00Z");
        jdbcTemplate.update("UPDATE crm_settlement_batch SET paid_fen=5 WHERE tenant_id=1 AND id=?", batchId);
        Timestamp before = jdbcTemplate.queryForObject("SELECT updated_at FROM crm_settlement_batch WHERE tenant_id=1 AND id=?",
                Timestamp.class, batchId);

        JsonNode report = data(get("/api/v1/settlements/reconciliation?institutionId=" + institutionId)
                .cookie(finance.cookies()));
        assertThat(report.at("/issueCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(report.findValues("checkType").stream().map(JsonNode::asText))
                .contains("SETTLEMENT_PAYMENT", "UNBATCHED_REDEMPTION");
        assertThat(report.findValues("resourceId").stream().map(JsonNode::asLong))
                .contains(batchId, unbatchedRedemption);
        Timestamp after = jdbcTemplate.queryForObject("SELECT updated_at FROM crm_settlement_batch WHERE tenant_id=1 AND id=?",
                Timestamp.class, batchId);
        assertThat(after).isEqualTo(before);
        assertThat(queryLong("SELECT paid_fen FROM crm_settlement_batch WHERE tenant_id=1 AND id=?", batchId)).isEqualTo(5);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_line WHERE tenant_id=1 AND redemption_id=?", unbatchedRedemption))
                .isZero();
    }

    @Test
    void requestedAndApprovedReversalsBlockSettlementAndDisappearFromReconciliationUntilRejected() throws Exception {
        long institutionId = newInstitution("MONTHLY");
        long requestedRedemption = newRedemption(institutionId, "POSTED", "2026-10-20T08:00:00Z");
        long approvedRedemption = newRedemption(institutionId, "POSTED", "2026-10-21T08:00:00Z");

        insertReversal(requestedRedemption, institutionId, "REQUESTED", requestedRedemption);
        insertReversal(approvedRedemption, institutionId, "APPROVED", approvedRedemption);

        JsonNode beforeReject = data(get("/api/v1/settlements/reconciliation?institutionId=" + institutionId)
                .cookie(finance.cookies()));
        assertThat(beforeReject.findValues("resourceId").stream().map(JsonNode::asLong))
                .doesNotContain(requestedRedemption, approvedRedemption);

        mockMvc.perform(finance.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", key("c01-req")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody(institutionId, "MONTHLY", "2026-10", List.of(requestedRedemption)))))
                .andExpect(status().isConflict());
        mockMvc.perform(finance.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", key("c01-app")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody(institutionId, "MONTHLY", "2026-10", List.of(approvedRedemption)))))
                .andExpect(status().isConflict());

        jdbcTemplate.update("UPDATE crm_redemption_reversal SET status='REJECTED', active_redemption_id=NULL "
                + "WHERE tenant_id=1 AND redemption_id=?", requestedRedemption);

        JsonNode afterReject = data(get("/api/v1/settlements/reconciliation?institutionId=" + institutionId)
                .cookie(finance.cookies()));
        assertThat(afterReject.findValues("resourceId").stream().map(JsonNode::asLong))
                .contains(requestedRedemption)
                .doesNotContain(approvedRedemption);

        JsonNode created = data(finance.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", key("c01-after-reject")).contentType(MediaType.APPLICATION_JSON)
                .content(json(createBody(institutionId, "MONTHLY", "2026-10", List.of(requestedRedemption)))));
        assertThat(created.at("/lines/0/redemptionId").asLong()).isEqualTo(requestedRedemption);
    }

    @Test
    void verifyDefenseInDepthRejectsBatchIfLineHasActiveReversal() throws Exception {
        long institutionId = newInstitution("MONTHLY");
        long redemptionId = newRedemption(institutionId, "POSTED", "2026-10-20T08:00:00Z");
        long batchId = newBatch(institutionId, redemptionId);

        insertReversal(redemptionId, institutionId, "REQUESTED", redemptionId);

        mockMvc.perform(verification(batchId, 0, 1000, 100, 900))
                .andExpect(status().isConflict());
        assertThat(queryString("SELECT status FROM crm_settlement_batch WHERE tenant_id=1 AND id=?", batchId))
                .isEqualTo("DRAFT");
    }

    @Test
    void monthlyAllowsDisjointLateRedemptionsWhileBlockingOverlapAndPerRedemptionDuplicates() throws Exception {
        long institutionId = newInstitution("MONTHLY");
        jdbcTemplate.update("UPDATE crm_institution SET settlement_day='8' WHERE tenant_id=1 AND id=?", institutionId);
        long r1 = newRedemption(institutionId, "POSTED", "2026-10-20T08:00:00Z");
        long r2 = newRedemption(institutionId, "POSTED", "2026-10-25T08:00:00Z");
        long r3 = newRedemption(institutionId, "POSTED", "2026-10-26T08:00:00Z");

        JsonNode first = data(finance.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", key("mbe06-b1")).contentType(MediaType.APPLICATION_JSON)
                .content(json(createBody(institutionId, "MONTHLY", "2026-10", List.of(r1)))));
        long batch1Id = first.at("/batch/id").asLong();
        data(verification(batch1Id, 0, 1000, 100, 900));
        data(payment(batch1Id, 1, 900, key("MBE06-PAY1"), 9001, "paid-b1"));

        JsonNode second = data(finance.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", key("mbe06-b2")).contentType(MediaType.APPLICATION_JSON)
                .content(json(createBody(institutionId, "MONTHLY", "2026-10", List.of(r2)))));
        long batch2Id = second.at("/batch/id").asLong();
        assertThat(batch2Id).isNotEqualTo(batch1Id);
        assertThat(second.at("/batch/periodKey").asText()).isEqualTo("2026-10");
        assertThat(second.at("/batch/settlementDate").asText()).isEqualTo("2026-10-08");

        mockMvc.perform(finance.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", key("mbe06-overlap")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody(institutionId, "MONTHLY", "2026-10", List.of(r1, r3)))))
                .andExpect(status().isConflict());
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_line WHERE tenant_id=1 AND redemption_id=?", r3))
                .isZero();

        long perInstitution = newInstitution("PER_REDEMPTION");
        long r4 = newRedemption(perInstitution, "POSTED", "2026-10-20T08:00:00Z");
        JsonNode per1 = data(finance.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", key("mbe06-per-1")).contentType(MediaType.APPLICATION_JSON)
                .content(json(createBody(perInstitution, "PER_REDEMPTION", "REDEMPTION:" + r4, List.of(r4)))));
        JsonNode perReplay = data(finance.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", key("mbe06-per-2")).contentType(MediaType.APPLICATION_JSON)
                .content(json(createBody(perInstitution, "PER_REDEMPTION", "REDEMPTION:" + r4, List.of(r4)))));
        assertThat(perReplay.at("/batch/id").asLong()).isEqualTo(per1.at("/batch/id").asLong());
        assertThat(queryInt("SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='crm_settlement_batch' "
                + "AND INDEX_NAME='uk_settlement_batch_per_redemption' AND NON_UNIQUE=0")).isGreaterThanOrEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO crm_settlement_batch (tenant_id,institution_id,settlement_type,period_key,status,amount_status) "
                        + "VALUES (1,?,'PER_REDEMPTION',?,'DRAFT','PENDING_VERIFICATION')",
                perInstitution, "REDEMPTION:" + r4))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void zeroNetPayableBatchClosesToPaidOnZeroFenPaymentAndRejectsInvalidZeroOrNegative() throws Exception {
        long institutionId = newInstitution("MONTHLY");
        long rZero = newRedemption(institutionId, "POSTED", "2026-10-20T08:00:00Z");
        long zeroBatchId = newBatch(institutionId, rZero);

        JsonNode verifiedZero = data(verification(zeroBatchId, 0, 1000, 1000, 0));
        assertThat(verifiedZero.at("/batch/status").asText()).isEqualTo("CONFIRMED");
        assertThat(verifiedZero.at("/batch/netPayableFen").asLong()).isZero();

        mockMvc.perform(payment(zeroBatchId, 1, 1, key("ZERO-OVER"), 9001, "over"))
                .andExpect(status().isConflict());
        mockMvc.perform(payment(zeroBatchId, 1, -1, key("ZERO-NEG"), 9001, "neg"))
                .andExpect(status().isBadRequest());

        JsonNode paidZero = data(payment(zeroBatchId, 1, 0, key("ZERO-OK"), 9001, "zero-close"));
        assertThat(paidZero.at("/batch/status").asText()).isEqualTo("PAID");
        assertThat(paidZero.at("/batch/paidFen").asLong()).isZero();

        mockMvc.perform(payment(zeroBatchId, 2, 0, key("ZERO-DUP"), 9001, "dup"))
                .andExpect(status().isConflict());

        long rNonZero = newRedemption(institutionId, "POSTED", "2026-10-21T08:00:00Z");
        long nonZeroBatchId = newBatch(institutionId, rNonZero);
        data(verification(nonZeroBatchId, 0, 1000, 100, 900));
        mockMvc.perform(payment(nonZeroBatchId, 1, 0, key("NONZERO-ZERO"), 9001, "bad-zero"))
                .andExpect(status().isBadRequest());
    }

    private void insertReversal(long redemptionId, long institutionId, String status, Long activeRedemptionId) {
        long customerId = queryLong("SELECT customer_id FROM crm_redemption WHERE tenant_id=1 AND id=?", redemptionId);
        jdbcTemplate.update("INSERT INTO crm_redemption_reversal (tenant_id,redemption_id,customer_id,institution_id,"
                        + "owner_staff_id,status,reason,requested_by,requested_at,request_idempotency_key,active_redemption_id) "
                        + "VALUES (1,?,?,?,2,?,'c01-test',2,NOW(3),?,?)",
                redemptionId, customerId, institutionId, status, key("rev-row"), activeRedemptionId);
    }

    private long newBatch(long institutionId, long redemptionId) throws Exception {
        return data(finance.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", key("helper-batch")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody(institutionId, "MONTHLY", "2026-10", List.of(redemptionId)))))
                .at("/batch/id").asLong();
    }

    private long newInstitution(String settlementType) {
        String no = "IT-SETTLE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("INSERT INTO crm_institution (tenant_id,institution_no,full_name,status,settlement_type,"
                + "online_rate,offline_rate,platform_rate) VALUES (1,?,?,'ACTIVE',?,0.18,0.12,0.15)", no, "合成结算机构", settlementType);
        return queryLong("SELECT id FROM crm_institution WHERE tenant_id=1 AND institution_no=?", no);
    }

    private long newRedemption(long institutionId, String redemptionStatus, String serviceAt) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,customer_no,display_name) VALUES (1,2,?,'合成结算客户')",
                "IT-SETTLE-C-" + suffix);
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?", "IT-SETTLE-C-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_appointment (tenant_id,appointment_no,customer_id,institution_id,owner_staff_id,"
                        + "scheduled_at,status,created_by) VALUES (1,?,?,2,?,?,'REDEEMED',2)",
                "IT-SETTLE-A-" + suffix, customerId, institutionId, Timestamp.from(Instant.parse(serviceAt)));
        long appointmentId = queryLong("SELECT id FROM crm_appointment WHERE tenant_id=1 AND appointment_no=?", "IT-SETTLE-A-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_redemption (tenant_id,appointment_id,customer_id,institution_id,owner_staff_id,"
                        + "service_at,evidence_ref,status,settlement_status,idempotency_key,posted_by,posted_at) "
                        + "VALUES (1,?,?,?,2,?,'synthetic-evidence',?,'PENDING_VERIFICATION',?,10,?)",
                appointmentId, customerId, institutionId, Timestamp.from(Instant.parse(serviceAt)), redemptionStatus,
                "IT-SETTLE-R-" + suffix, Timestamp.from(Instant.parse(serviceAt)));
        return queryLong("SELECT id FROM crm_redemption WHERE tenant_id=1 AND idempotency_key=?", "IT-SETTLE-R-" + suffix);
    }

    private MockHttpServletRequestBuilder verification(long batchId, long version, long gross, long commission, long net)
            throws Exception {
        return finance.withAuth(post("/api/v1/settlements/batches/" + batchId + "/verification"))
                .header("Idempotency-Key", key("verify")).contentType(MediaType.APPLICATION_JSON)
                .content(json(verifyBody(version, gross, commission, net)));
    }

    private MockHttpServletRequestBuilder payment(long batchId, long version, long amount, String bankTradeNo,
                                                  long fileId, String evidence) throws Exception {
        return finance.withAuth(post("/api/v1/settlements/batches/" + batchId + "/payments"))
                .header("Idempotency-Key", key("pay")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", version, "amountFen", amount, "bankTradeNo", bankTradeNo,
                        "paidAt", Instant.now().toString(), "voucherFileId", fileId, "evidenceRef", evidence)));
    }

    private Map<String, Object> createBody(long institutionId, String type, String period, List<Long> redemptionIds) {
        return Map.of("institutionId", institutionId, "settlementType", type, "periodKey", period, "redemptionIds", redemptionIds);
    }

    private Map<String, Object> verifyBody(long version, long gross, long commission, long net) {
        return Map.of("version", version, "ruleVersion", "RULE-2026-10", "grossFen", gross,
                "commissionFen", commission, "netPayableFen", net, "verificationEvidence", "offline-approved");
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return "it-settlement-" + prefix + "-" + UUID.randomUUID();
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
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

    @TestConfiguration
    static class FileConfig {
        @Bean
        @Primary
        FileApplicationService fileApplicationService() {
            FileApplicationService service = org.mockito.Mockito.mock(FileApplicationService.class);
            org.mockito.Mockito.when(service.requireCleanUsable(org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.eq(FileClassification.PAYMENT), org.mockito.ArgumentMatchers.isNull()))
                    .thenAnswer(invocation -> {
                        if (!Set.of(9001L).contains(invocation.getArgument(2))) {
                            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "测试文件未扫描通过");
                        }
                        return null;
                    });
            return service;
        }
    }
}
