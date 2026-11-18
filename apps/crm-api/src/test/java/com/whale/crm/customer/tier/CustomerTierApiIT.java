package com.whale.crm.customer.tier;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.customer.tier.application.CustomerTierRecalculationService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerTierApiIT extends AbstractMysqlIT {
    private static final Instant AS_OF = Instant.parse("2025-03-01T00:00:00Z");
    private static final Instant START = Instant.parse("2024-03-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CustomerTierRecalculationService recalculationService;

    @Test
    void actualConsumptionUsesHalfOpenWindowAndUpgradeIsTransactionalAndIdempotent() throws Exception {
        AuthCookies admin = login("admin");
        long ruleId = createActiveRule(admin, "UTC");
        long customerId = customer(1L, 2L, "D");
        long includedOrder = order(1L, customerId, "PAID", 5000L, START, START);
        order(1L, customerId, "FULFILLED", 7000L, AS_OF, START.plusSeconds(86400));
        order(1L, customerId, "DRAFT", 9000L, START.plusSeconds(100), START.plusSeconds(100));
        order(1L, customerId, "CANCELED", 9000L, START.plusSeconds(100), START.plusSeconds(100));
        order(1L, customerId, "PAID", 9000L, START.minusSeconds(1), START.plusSeconds(200));
        payment(1L, includedOrder, "CONFIRMED", 1100L, START);
        payment(1L, includedOrder, "CONFIRMED", 2200L, AS_OF);
        payment(1L, includedOrder, "PENDING", 3300L, START.plusSeconds(1));
        long refundId = refund(1L, includedOrder, customerId);
        refundPayment(1L, refundId, "SUCCEEDED", 400L, START);
        refundPayment(1L, refundId, "SUCCEEDED", 500L, AS_OF);
        refundPayment(1L, refundId, "FAILED", 600L, START.plusSeconds(1));
        followup(1L, customerId, START, false);
        followup(1L, customerId, AS_OF, false);
        followup(1L, customerId, START.plusSeconds(1), true);

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-tier-rules/{id}/trial", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"asOf\":\"" + AS_OF + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.windowStartInclusive").value(START.toString()))
                .andExpect(jsonPath("$.data.windowEndExclusive").value(AS_OF.toString()))
                .andExpect(jsonPath("$.data.paymentAmountFen").value(1100))
                .andExpect(jsonPath("$.data.paidAmountFen").value(5000))
                .andExpect(jsonPath("$.data.refundAmountFen").value(400))
                .andExpect(jsonPath("$.data.actualConsumptionFen").value(4600))
                .andExpect(jsonPath("$.data.orderCount").value(3))
                .andExpect(jsonPath("$.data.manualFollowupCount").value(1))
                .andExpect(jsonPath("$.data.archivedMessageCount").value(0))
                .andExpect(jsonPath("$.data.communicationCount").value(1))
                .andExpect(jsonPath("$.data.score").doesNotExist())
                .andExpect(jsonPath("$.data.suggestedTierCode").value("B"));

        String response = recalculate(admin, customerId, 0, "tier-metric-recalc", AS_OF);
        long snapshotId = objectMapper.readTree(response).at("/data/snapshotId").asLong();
        assertThat(snapshotId).isPositive();
        assertThat(objectMapper.readTree(response).at("/data/actualConsumptionFen").asLong()).isEqualTo(4600L);
        assertThat(jdbcTemplate.queryForObject("SELECT actual_consumption_fen FROM "
                + "crm_customer_tier_metric_snapshot WHERE id=?", Long.class, snapshotId)).isEqualTo(4600L);
        assertThat(jdbcTemplate.queryForObject("SELECT tier_code FROM crm_customer WHERE id=?",
                String.class, customerId)).isEqualTo("B");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_history "
                + "WHERE customer_id=? AND change_type='AUTO_UPGRADE'", Long.class, customerId)).isEqualTo(1);

        String replay = recalculate(admin, customerId, 0, "tier-metric-recalc", AS_OF);
        assertThat(objectMapper.readTree(replay).at("/data/snapshotId").asLong()).isEqualTo(snapshotId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_metric_snapshot "
                + "WHERE customer_id=?", Long.class, customerId)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/customers/{id}/tier-history", customerId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fromTierCode").value("D"))
                .andExpect(jsonPath("$.data[0].toTierCode").value("B"));
    }

    @Test
    void archiveGapLeavesCommunicationUnknownButAmountStillDeterminesTier() throws Exception {
        AuthCookies admin = login("admin");
        long ruleId = createActiveRule(admin, "UTC");
        long customerId = customer(1L, 2L, "A");
        jdbcTemplate.update("INSERT INTO crm_wecom_contact "
                        + "(tenant_id,corp_id,external_userid,staff_user_id,customer_id,consent_status) "
                        + "VALUES (1,?,?,2,?,'GRANTED')",
                "tier-gap-corp", "tier-gap-" + customerId, customerId);

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-tier-rules/{id}/trial", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"asOf\":\"" + AS_OF + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.communicationComplete").value(false))
                .andExpect(jsonPath("$.data.communicationCount").doesNotExist())
                .andExpect(jsonPath("$.data.actualConsumptionFen").value(0))
                .andExpect(jsonPath("$.data.score").doesNotExist())
                .andExpect(jsonPath("$.data.suggestedTierCode").value("D"));
    }

    @Test
    void downgradeNeedsApprovalAndNewSnapshotStalesOldReview() throws Exception {
        AuthCookies admin = login("admin");
        createActiveRule(admin, "UTC");
        long customerId = customer(1L, 2L, "S");
        recalculate(admin, customerId, 0, "tier-downgrade-1", AS_OF);
        long firstReview = pendingReview(customerId);
        assertThat(jdbcTemplate.queryForObject("SELECT tier_code FROM crm_customer WHERE id=?",
                String.class, customerId)).isEqualTo("S");
        assertThat(jdbcTemplate.queryForObject("SELECT channels FROM crm_notification WHERE source_type="
                + "'CUSTOMER_TIER_REVIEW' AND source_id=?", String.class, Long.toString(firstReview)))
                .isEqualTo("IN_APP,WECOM_APP");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_notification_delivery d "
                + "JOIN crm_notification n ON n.id=d.notification_id "
                + "WHERE n.source_type='CUSTOMER_TIER_REVIEW' AND n.source_id=?",
                Long.class, Long.toString(firstReview))).isEqualTo(2);

        recalculate(admin, customerId, 0, "tier-downgrade-2", AS_OF);
        long secondReview = pendingReview(customerId);
        assertThat(secondReview).isNotEqualTo(firstReview);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_customer_tier_downgrade_review WHERE id=?",
                String.class, firstReview)).isEqualTo("STALE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_downgrade_review "
                + "WHERE customer_id=? AND status='PENDING_REVIEW'", Long.class, customerId)).isEqualTo(1);

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-tier-reviews/{id}/approve", firstReview))
                        .header("Idempotency-Key", "tier-approve-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"旧单不应批准\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-tier-reviews/{id}/approve", secondReview))
                        .header("Idempotency-Key", "tier-approve-" + secondReview)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"确认滚动指标下降\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.effectiveAt").isNotEmpty());
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-tier-reviews/{id}/approve", secondReview))
                        .header("Idempotency-Key", "tier-approve-" + secondReview)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"确认滚动指标下降\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(jdbcTemplate.queryForObject("SELECT tier_code FROM crm_customer WHERE id=?",
                String.class, customerId)).isEqualTo("D");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_history "
                + "WHERE review_id=? AND change_type='DOWNGRADE_APPROVED'", Long.class, secondReview)).isEqualTo(1);
    }

    @Test
    void activatingAnotherRuleStalesPendingReviewWithoutChangingTier() throws Exception {
        AuthCookies admin = login("admin");
        createActiveRule(admin, "UTC");
        long customerId = customer(1L, 2L, "S");
        recalculate(admin, customerId, 0, "tier-rule-stale", AS_OF);
        long reviewId = pendingReview(customerId);

        createActiveRule(admin, "Asia/Shanghai");

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_customer_tier_downgrade_review WHERE id=?",
                String.class, reviewId)).isEqualTo("STALE");
        assertThat(jdbcTemplate.queryForObject("SELECT tier_code FROM crm_customer WHERE id=?",
                String.class, customerId)).isEqualTo("S");
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-tier-reviews/{id}/reject", reviewId))
                        .header("Idempotency-Key", "tier-rule-stale-reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"旧规则单不可处理\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectAndManualLockNeverOverwriteHistoryAndGenericTierWriteIsRejected() throws Exception {
        AuthCookies admin = login("admin");
        createActiveRule(admin, "UTC");
        long rejectedCustomer = customer(1L, 2L, "A");
        recalculate(admin, rejectedCustomer, 0, "tier-reject-request", AS_OF);
        long rejectedReview = pendingReview(rejectedCustomer);
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-tier-reviews/{id}/reject", rejectedReview))
                        .header("Idempotency-Key", "tier-reject-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"客户仍有服务价值\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        assertThat(jdbcTemplate.queryForObject("SELECT tier_code FROM crm_customer WHERE id=?",
                String.class, rejectedCustomer)).isEqualTo("A");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_history "
                + "WHERE review_id=?", Long.class, rejectedReview)).isZero();

        long customerId = customer(1L, 2L, "D");
        mockMvc.perform(admin.withAuth(post("/api/v1/customers/{id}/tier-adjustments", customerId))
                        .header("Idempotency-Key", "tier-manual-lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierCode\":\"A\",\"reason\":\"人工核实高价值客户\","
                                + "\"lock\":true,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tierCode").value("A"))
                .andExpect(jsonPath("$.data.locked").value(true));
        recalculate(admin, customerId, 1, "tier-locked-recalc", AS_OF);
        assertThat(pendingReviewCount(customerId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT tier_code FROM crm_customer WHERE id=?",
                String.class, customerId)).isEqualTo("A");

        mockMvc.perform(admin.withAuth(patch("/api/v1/customers/{id}", customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierCode\":\"D\",\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(admin.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"禁止通用等级\",\"tierCode\":\"S\",\"ownerStaffId\":2}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_history "
                + "WHERE customer_id=? AND change_type='MANUAL_ADJUST'", Long.class, customerId)).isEqualTo(1);
    }

    @Test
    void customerPatchStalesReviewAndScopeCsrfAndTenantAreEnforced() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager2 = login("manager2");
        createActiveRule(admin, "UTC");
        long customerId = customer(1L, 2L, "S");
        recalculate(admin, customerId, 0, "tier-scope-review", AS_OF);
        long reviewId = pendingReview(customerId);

        mockMvc.perform(get("/api/v1/customers/{id}/tier", customerId).cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customer-tier-reviews").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(manager2.withAuth(post("/api/v1/customer-tier-reviews/{id}/approve", reviewId))
                        .header("Idempotency-Key", "tier-cross-scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"越权尝试\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/customer-tier-rules").cookie(admin.cookies())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(admin.withAuth(patch("/api/v1/customers/{id}", customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"资料变化使复核失效\",\"version\":0}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_customer_tier_downgrade_review WHERE id=?",
                String.class, reviewId)).isEqualTo("STALE");

        long otherTenant = customer(2L, 2L, "S");
        mockMvc.perform(get("/api/v1/customers/{id}/tier", otherTenant).cookie(admin.cookies()))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentSameRecalculationCreatesOnlyOneSnapshotAndPendingReview() throws Exception {
        AuthCookies admin = login("admin");
        createActiveRule(admin, "UTC");
        long customerId = customer(1L, 2L, "S");
        int workers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    recalculationService.recalculate(1L, customerId, AS_OF, "MANUAL",
                            "tier-concurrent-" + customerId, 1L);
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_metric_snapshot "
                + "WHERE tenant_id=1 AND recalculation_key=?", Long.class,
                "tier-concurrent-" + customerId)).isEqualTo(1);
        assertThat(pendingReviewCount(customerId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT tier_code FROM crm_customer WHERE id=?",
                String.class, customerId)).isEqualTo("S");
    }

    @Test
    void concurrentApiReplayAfterUpgradeReturnsSameSnapshotWithoutDuplicateHistory() throws Exception {
        AuthCookies admin = login("admin");
        createActiveRule(admin, "UTC");
        long customerId = customer(1L, 2L, "D");
        order(1L, customerId, "PAID", 1_000_000L, START, START);
        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        String key = "tier-api-concurrent-" + customerId;
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return recalculate(admin, customerId, 0, key, AS_OF);
                }));
            }
            ready.await();
            start.countDown();
            List<Long> snapshotIds = new ArrayList<>();
            for (Future<String> future : futures) {
                snapshotIds.add(objectMapper.readTree(future.get()).at("/data/snapshotId").asLong());
            }
            assertThat(snapshotIds).hasSize(workers);
            assertThat(snapshotIds.stream().distinct()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT tier_code FROM crm_customer WHERE id=?",
                String.class, customerId)).isEqualTo("S");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_history "
                + "WHERE customer_id=? AND change_type='AUTO_UPGRADE'", Long.class, customerId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_idempotency_record "
                + "WHERE tenant_id=1 AND scope_code='customer:tier:recalculate' AND idempotency_key=?",
                Long.class, key)).isEqualTo(1);
    }

    @Test
    void legacyScoringRuleCanBeViewedButNeverAutomaticallyChangesTier() throws Exception {
        AuthCookies admin = login("admin");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String ruleCode = "TIER_LEGACY_" + suffix;
        String metrics = "[{\"metricCode\":\"PAID_AMOUNT_FEN\",\"direction\":\"GTE\",\"weight\":100,"
                + "\"thresholds\":{\"S\":1,\"A\":1,\"B\":1,\"C\":1,\"D\":0}}]";
        jdbcTemplate.update("UPDATE crm_customer_tier_rule SET status='RETIRED',retired_at=CURRENT_TIMESTAMP(3),"
                + "version=version+1 WHERE tenant_id=1 AND status='ACTIVE'");
        jdbcTemplate.update("INSERT INTO crm_customer_tier_rule (tenant_id,rule_code,version_no,rule_name,zone_id,"
                + "status,metric_config_json,created_by,activated_at) VALUES (1,?,1,'历史评分规则','UTC',"
                + "'ACTIVE',?,1,CURRENT_TIMESTAMP(3))", ruleCode, metrics);
        long ruleId = jdbcTemplate.queryForObject("SELECT id FROM crm_customer_tier_rule WHERE tenant_id=1 "
                + "AND rule_code=?", Long.class, ruleCode);
        jdbcTemplate.update("INSERT INTO crm_customer_tier_level (tenant_id,rule_id,tier_code,tier_name,sort_no,"
                + "min_score) VALUES (1,?,'S','核心',1,90),(1,?,'A','重要',2,70),(1,?,'B','成长',3,50),"
                + "(1,?,'C','普通',4,20),(1,?,'D','培育',5,0)", ruleId, ruleId, ruleId, ruleId, ruleId);
        long customerId = customer(1L, 2L, "D");
        order(1L, customerId, "PAID", 1_000_000L, START, START);

        mockMvc.perform(get("/api/v1/customer-tier-rules/{id}", ruleId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ruleCode").value(ruleCode))
                .andExpect(jsonPath("$.data.levels[0].minScore").value(90.0))
                .andExpect(jsonPath("$.data.levels[0].minConsumptionFen").doesNotExist());
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-tier-rules/{id}/trial", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"asOf\":\"" + AS_OF + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualConsumptionFen").value(1_000_000L))
                .andExpect(jsonPath("$.data.score").doesNotExist())
                .andExpect(jsonPath("$.data.suggestedTierCode").doesNotExist());
        recalculate(admin, customerId, 0, "tier-legacy-no-auto", AS_OF);

        assertThat(jdbcTemplate.queryForObject("SELECT tier_code FROM crm_customer WHERE id=?",
                String.class, customerId)).isEqualTo("D");
        assertThat(pendingReviewCount(customerId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_history "
                + "WHERE customer_id=?", Long.class, customerId)).isZero();
    }

    private long createActiveRule(AuthCookies admin, String timezone) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        List<Map<String, Object>> levels = new ArrayList<>();
        levels.add(level("S", "核心", 1, "0", 1_000_000L));
        levels.add(level("A", "重要", 2, "0", 800_001L));
        levels.add(level("B", "成长", 3, "0", 1000L));
        levels.add(level("C", "普通", 4, "0", 1L));
        levels.add(level("D", "培育", 5, "0", 0L));
        Map<String, Object> request = Map.of(
                "ruleCode", "TIER_" + suffix,
                "ruleName", "集成测试规则" + suffix,
                "timezone", timezone,
                "levels", levels,
                "metrics", List.of());
        String response = mockMvc.perform(admin.withAuth(post("/api/v1/customer-tier-rules"))
                        .header("Idempotency-Key", "tier-create-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.levels[0].minConsumptionFen").value(1_000_000L))
                .andExpect(jsonPath("$.data.metrics").isEmpty())
                .andReturn().getResponse().getContentAsString();
        long ruleId = objectMapper.readTree(response).at("/data/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-tier-rules/{id}/activation", ruleId))
                        .header("Idempotency-Key", "tier-activate-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mockMvc.perform(admin.withAuth(putRule(ruleId))
                        .header("Idempotency-Key", "tier-frozen-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
        return ruleId;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putRule(long ruleId) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/customer-tier-rules/{id}?version=1", ruleId);
    }

    private Map<String, Object> level(String code, String name, int sort, String minScore,
                                      long minConsumptionFen) {
        return Map.of("tierCode", code, "tierName", name, "sortNo", sort, "minScore", minScore,
                "minConsumptionFen", minConsumptionFen);
    }

    private String recalculate(AuthCookies admin, long customerId, long version, String key, Instant asOf)
            throws Exception {
        return mockMvc.perform(admin.withAuth(post("/api/v1/customers/{id}/tier-recalculations", customerId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"asOf\":\"" + asOf + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long customer(long tenantId, long ownerId, String tier) {
        String number = "TIERC" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,nickname,tier_code) "
                + "VALUES (?,?,1,?,?,?)", tenantId, ownerId, number, "分级测试" + number, tier);
        return jdbcTemplate.queryForObject("SELECT id FROM crm_customer WHERE tenant_id=? AND customer_no=?",
                Long.class, tenantId, number);
    }

    private long order(long tenantId, long customerId, String status, long paidFen,
                       Instant paidAt, Instant orderedAt) {
        String number = "TIERO" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "source_system,order_type_code,status,receiver_type,paid_fen,ordered_at,paid_at) "
                        + "VALUES (?,?,?,2,2,'CRM','REGULAR',?,'PLATFORM',?,?,?)",
                tenantId, number, customerId, status, paidFen, Timestamp.from(orderedAt), Timestamp.from(paidAt));
        return jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE tenant_id=? AND order_no=?",
                Long.class, tenantId, number);
    }

    private void payment(long tenantId, long orderId, String status, long amount, Instant confirmedAt) {
        jdbcTemplate.update("INSERT INTO crm_payment_txn (tenant_id,order_id,payment_channel,receiver_type,"
                        + "external_trade_no,amount_fen,status,confirmed_at) VALUES (?,?,'BANK','PLATFORM',?,?,?,?)",
                tenantId, orderId, "TIERP" + UUID.randomUUID(), amount, status, Timestamp.from(confirmedAt));
    }

    private long refund(long tenantId, long orderId, long customerId) {
        String number = "TIERR" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("INSERT INTO crm_refund (tenant_id,refund_no,order_id,customer_id,requested_fen,"
                        + "approved_fen,succeeded_fen,reason_code,status,requested_by) "
                        + "VALUES (?,?,?,?,1000,1000,1000,'SERVICE','SUCCEEDED',1)",
                tenantId, number, orderId, customerId);
        return jdbcTemplate.queryForObject("SELECT id FROM crm_refund WHERE tenant_id=? AND refund_no=?",
                Long.class, tenantId, number);
    }

    private void refundPayment(long tenantId, long refundId, String status, long amount, Instant succeededAt) {
        jdbcTemplate.update("INSERT INTO crm_refund_payment (tenant_id,refund_id,provider,external_refund_no,"
                        + "amount_fen,status,verification_method,succeeded_at) VALUES (?,?,'BANK',?,?,?,'MANUAL_REVIEW',?)",
                tenantId, refundId, "TIERRP" + UUID.randomUUID(), amount, status, Timestamp.from(succeededAt));
    }

    private void followup(long tenantId, long customerId, Instant contactAt, boolean deleted) {
        jdbcTemplate.update("INSERT INTO crm_followup (tenant_id,customer_id,owner_staff_id,channel_code,contact_at,"
                        + "created_by,deleted) VALUES (?,?,2,'PHONE',?,1,?)",
                tenantId, customerId, Timestamp.from(contactAt), deleted);
    }

    private long pendingReview(long customerId) {
        return jdbcTemplate.queryForObject("SELECT id FROM crm_customer_tier_downgrade_review "
                + "WHERE customer_id=? AND status='PENDING_REVIEW'", Long.class, customerId);
    }

    private long pendingReviewCount(long customerId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tier_downgrade_review "
                + "WHERE customer_id=? AND status='PENDING_REVIEW'", Long.class, customerId);
    }
}
