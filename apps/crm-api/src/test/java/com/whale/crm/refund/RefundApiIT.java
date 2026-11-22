package com.whale.crm.refund;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.customer.application.MergeApplicationService;
import com.whale.crm.fulfillment.api.dto.PostRedemptionRequest;
import com.whale.crm.fulfillment.api.dto.RedemptionLineRequest;
import com.whale.crm.fulfillment.application.RedemptionApplicationService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.product.domain.CatalogRepository;
import com.whale.crm.product.domain.PackageVersion;
import com.whale.crm.product.domain.ProductPackage;
import com.whale.crm.product.domain.Project;
import com.whale.crm.product.domain.Sku;
import com.whale.crm.refund.api.dto.ManualRefundSuccessRequest;
import com.whale.crm.refund.application.MockRefundProviderAdapter;
import com.whale.crm.refund.application.RefundApplicationService;
import com.whale.crm.refund.domain.Refund;
import com.whale.crm.refund.domain.RefundItem;
import com.whale.crm.refund.domain.RefundPayment;
import com.whale.crm.refund.domain.RefundRepository;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementLedgerEntry;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.outbox.relay.enabled=false", "app.refunds.mock.enabled=true"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RefundApplicationService refundService;
    @Autowired
    private CurrentUserService currentUserService;
    @Autowired
    private MockRefundProviderAdapter mockAdapter;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private CatalogRepository catalogRepository;
    @Autowired
    private TradingRepository tradingRepository;
    @Autowired
    private RedemptionApplicationService redemptionService;
    @Autowired
    private MergeApplicationService mergeService;

    private AuthCookies admin;
    private AuthCookies manager1;
    private AuthCookies manager2;
    private AuthCookies finance;
    private AuthCookies operator1;
    private long multiProjectSku;

    @BeforeAll
    void setUp() throws Exception {
        admin = login("admin");
        manager1 = login("manager1");
        manager2 = login("manager2");
        finance = login("finance1");
        operator1 = login("operator1");
        multiProjectSku = createTwoProjectSku();
    }

    @Test
    void r01RequestedApprovedAndProcessingNeverChangeAssets() throws Exception {
        PaidOrder paid = paidOrder(301, 181100);
        long entitlementId = entitlementIds(paid.orderId()).get(0);
        String createKey = key("r01-create");
        JsonNode created = data(createRequest(manager1, paid.orderId(), 1000,
                List.of(item(entitlementId, 1, 1000)), createKey, "R01"));
        long refundId = created.at("/refund/id").asLong();
        assertThat(created.at("/refund/status").asText()).isEqualTo("REQUESTED");
        assertThat(data(createRequest(manager1, paid.orderId(), 1000,
                List.of(item(entitlementId, 1, 1000)), createKey, "R01")).at("/refund/id").asLong())
                .isEqualTo(refundId);
        mockMvc.perform(createRequest(manager1, paid.orderId(), 1001,
                        List.of(item(entitlementId, 1, 1001)), createKey, "R01-CHANGED"))
                .andExpect(status().isConflict());
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);

        JsonNode approved = data(reviewRequest(finance, refundId, 0, 1000, key("r01-review")));
        assertThat(approved.at("/refund/status").asText()).isEqualTo("APPROVED");
        assertThat(approved.at("/refund/succeededFen").asLong()).isZero();
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);
        mockMvc.perform(executeRequest(finance, refundId, 1, "BANK_TRANSFER", key("r01-external"), 999))
                .andExpect(status().isBadRequest());
        JsonNode processing = data(executeRequest(finance, refundId, 1, "BANK_TRANSFER", key("r01-external"), 1000));
        assertThat(processing.at("/refund/status").asText()).isEqualTo("PROCESSING");
        assertThat(processing.at("/payments/0/status").asText()).isEqualTo("PENDING");
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);
    }

    @Test
    void r02ConcurrentSuccessCreatesOneCashFactOneRevokeAndOutboxes() throws Exception {
        PaidOrder paid = paidOrder(301, 181100);
        long entitlementId = entitlementIds(paid.orderId()).get(0);
        long refundId = processingRefund(manager1, finance, paid.orderId(), 1000,
                List.of(item(entitlementId, 1, 1000)), "BANK_TRANSFER", key("r02-external"));
        long cleanFile = file("CLEAN");
        long pendingFile = file("PENDING");
        String externalRefundNo = queryString("SELECT external_refund_no FROM crm_refund_payment WHERE refund_id=?", refundId);
        ManualRefundSuccessRequest pending = manualRequest(externalRefundNo, 1000, pendingFile, "R02-PENDING");
        mockMvc.perform(successRequest(finance, refundId, pending, key("r02-pending")))
                .andExpect(status().isConflict());
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);

        long wrongClassFile = file("CLEAN");
        jdbcTemplate.update("UPDATE crm_file_object SET classification='MATERIAL' WHERE id=?", wrongClassFile);
        ManualRefundSuccessRequest wrongClass = manualRequest(externalRefundNo, 1000, wrongClassFile, "R02-WRONG-CLASS");
        mockMvc.perform(successRequest(finance, refundId, wrongClass, key("r02-wrong-class")))
                .andExpect(status().isConflict());
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);

        ManualRefundSuccessRequest request = manualRequest(externalRefundNo, 1000, cleanFile, "R02-CLEAN");
        CurrentUser actor = currentUserService.loadById(5).orElseThrow();
        String successKey = key("r02-success");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return objectMapper.valueToTree(refundService.succeedManually(1, actor, refundId, request, successKey));
                }));
            }
            start.countDown();
            for (Future<JsonNode> future : futures) {
                assertThat(future.get().at("/refund/status").asText()).isEqualTo("SUCCEEDED");
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", paid.orderId())).isEqualTo(1000);
        assertThat(queryLong("SELECT succeeded_fen FROM crm_refund WHERE id=?", refundId)).isEqualTo(1000);
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", entitlementId)).isEqualTo(2);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_refund_payment WHERE refund_id=? AND status='SUCCEEDED'", refundId)).isEqualTo(1);
        assertThat(queryLong("SELECT voucher_file_id FROM crm_refund_payment WHERE refund_id=? AND status='SUCCEEDED'", refundId)).isEqualTo(cleanFile);
        assertThat(queryString("SELECT evidence_ref FROM crm_refund_payment WHERE refund_id=? AND status='SUCCEEDED'", refundId)).isEqualTo("R02-CLEAN");
        JsonNode roundTripDetail = data(finance.withAuth(get("/api/v1/refunds/" + refundId)));
        assertThat(roundTripDetail.at("/payments/0/voucherFileId").asLong()).isEqualTo(cleanFile);
        assertThat(roundTripDetail.at("/payments/0/evidenceRef").asText()).isEqualTo("R02-CLEAN");
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type='REFUND_REVOKE'", entitlementId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?", "refund.succeeded.v1:" + refundId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?", "entitlement.refund_revoked.v1:" + refundId + ":" + entitlementId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE action_code='REFUND_SUCCEED' AND target_id=?", Long.toString(refundId))).isEqualTo(1);

        data(successRequest(finance, refundId, request, successKey));
        data(successRequest(finance, refundId, request, key("r02-natural-replay")));
        mockMvc.perform(successRequest(finance, refundId,
                        manualRequest(externalRefundNo, 1000, cleanFile, "R02-DIFFERENT"), successKey))
                .andExpect(status().isConflict());
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", paid.orderId())).isEqualTo(1000);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type='REFUND_REVOKE'", entitlementId)).isEqualTo(1);
    }

    @Test
    void failedCallbackDoesNotChangeAssetsAndNewExternalNumberCanRetry() throws Exception {
        PaidOrder paid = paidOrder(301, 181100);
        long entitlementId = entitlementIds(paid.orderId()).get(0);
        String firstExternal = key("mock-failed");
        long refundId = processingRefund(manager1, finance, paid.orderId(), 1000,
                List.of(item(entitlementId, 1, 1000)), "MOCK", firstExternal);
        String failedRaw = callbackJson(firstExternal, 1000, "FAILED", "MOCK-FAILED");
        mockMvc.perform(callback(failedRaw, key("callback-failed"), false))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(callback(failedRaw, key("callback-bad-signature"), true)
                        .header("X-Refund-Mock-Signature", "bad"))
                .andExpect(status().isUnauthorized());
        JsonNode failed = data(callback(failedRaw, key("callback-failed"), true));
        assertThat(failed.at("/refund/status").asText()).isEqualTo("FAILED");
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);

        String secondExternal = key("mock-succeeded");
        JsonNode retry = data(executeRequest(finance, refundId, 3, "MOCK", secondExternal, 1000));
        assertThat(retry.at("/refund/status").asText()).isEqualTo("PROCESSING");
        String succeededRaw = callbackJson(secondExternal, 1000, "SUCCEEDED", "MOCK-OK");
        String callbackKey = key("callback-success");
        data(callback(succeededRaw, callbackKey, true));
        data(callback(succeededRaw, callbackKey, true));
        data(callback(succeededRaw, key("callback-success-natural"), true));
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", paid.orderId())).isEqualTo(1000);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_refund_payment WHERE refund_id=? AND status='SUCCEEDED'", refundId)).isEqualTo(1);
        assertThat(queryString("SELECT evidence_ref FROM crm_refund_payment WHERE external_refund_no=?", firstExternal)).isEqualTo("MOCK-FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT voucher_file_id FROM crm_refund_payment WHERE external_refund_no=?", Long.class, firstExternal)).isNull();
        assertThat(queryString("SELECT evidence_ref FROM crm_refund_payment WHERE external_refund_no=?", secondExternal)).isEqualTo("MOCK-OK");
        assertThat(jdbcTemplate.queryForObject("SELECT voucher_file_id FROM crm_refund_payment WHERE external_refund_no=?", Long.class, secondExternal)).isNull();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type='REFUND_REVOKE'", entitlementId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?", "refund.succeeded.v1:" + refundId)).isEqualTo(1);

        PaidOrder preservePaid = paidOrder(301, 181100);
        long preserveEnt = entitlementIds(preservePaid.orderId()).get(0);
        String preserveExt = key("mock-preserve-ev");
        long preserveRefundId = processingRefund(manager1, finance, preservePaid.orderId(), 1000,
                List.of(item(preserveEnt, 1, 1000)), "MOCK", preserveExt);
        String nullEvidenceRaw = callbackJson(preserveExt, 1000, "SUCCEEDED", null);
        data(callback(nullEvidenceRaw, key("callback-preserve-ev"), true));
        assertThat(queryString("SELECT evidence_ref FROM crm_refund_payment WHERE refund_id=? AND status='SUCCEEDED'",
                preserveRefundId)).isEqualTo("IT-EXECUTE");
    }

    @Test
    void nthEntitlementFailureRollsBackCashEarlierRevokesOutboxAuditAndIdempotency() throws Exception {
        PaidOrder paid = paidOrder(multiProjectSku, 1000);
        List<Long> entitlementIds = entitlementIds(paid.orderId());
        long refundId = processingRefund(manager1, finance, paid.orderId(), 1000,
                List.of(item(entitlementIds.get(0), 1, 400), item(entitlementIds.get(1), 1, 600)),
                "BANK_TRANSFER", key("rollback-external"));
        long otherCustomer = createCustomer(admin, "IT-REFUND-OTHER", 3);
        jdbcTemplate.update("UPDATE crm_entitlement SET customer_id=? WHERE id=?", otherCustomer, entitlementIds.get(1));
        String external = queryString("SELECT external_refund_no FROM crm_refund_payment WHERE refund_id=?", refundId);
        String successKey = key("rollback-success");
        mockMvc.perform(successRequest(finance, refundId,
                        manualRequest(external, 1000, file("CLEAN"), "ROLLBACK"), successKey))
                .andExpect(status().isConflict());

        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", paid.orderId())).isZero();
        assertThat(queryString("SELECT status FROM crm_refund WHERE id=?", refundId)).isEqualTo("PROCESSING");
        assertThat(queryString("SELECT status FROM crm_refund_payment WHERE refund_id=?", refundId)).isEqualTo("PENDING");
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", entitlementIds.get(0))).isEqualTo(2);
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", entitlementIds.get(1))).isEqualTo(3);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id IN (?,?) AND txn_type='REFUND_REVOKE'", entitlementIds.get(0), entitlementIds.get(1))).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=? OR event_id LIKE ?", "refund.succeeded.v1:" + refundId, "entitlement.refund_revoked.v1:" + refundId + ":%")).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE action_code='REFUND_SUCCEED' AND target_id=?", Long.toString(refundId))).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_idempotency_record WHERE scope_code='refund.success' AND idempotency_key=?", successKey)).isZero();
    }

    @Test
    void selfReviewFunctionPermissionAndCustomerScopeAreEnforced() throws Exception {
        PaidOrder paid = paidOrder(301, 181100);
        long entitlementId = entitlementIds(paid.orderId()).get(0);
        long refundId = data(createRequest(admin, paid.orderId(), 1000,
                List.of(item(entitlementId, 1, 1000)), key("self-create"), "SELF")).at("/refund/id").asLong();
        mockMvc.perform(reviewRequest(admin, refundId, 0, 1000, key("self-review")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/refunds/" + refundId).cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(executeRequest(manager1, refundId, 0, "BANK_TRANSFER", key("forbidden"), 1000))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/refunds").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void c02CallbackRoutesToTenant2EvenWithTenant1CookieAndRejectsCrossTenantCollision() throws Exception {
        Tenant2RefundFixture t2 = seedTenant2ProcessingRefund("t2-route", 1000L, 3);
        PaidOrder t1Paid = paidOrder(301, 181100);
        String raw = callbackJson(t2.externalRefundNo(), 1000L, "SUCCEEDED", "T2-CB-OK");
        String cbKey = key("t2-cb");

        mockMvc.perform(callback(raw, cbKey, true).cookie(finance.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refund.status").value("SUCCEEDED"));

        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE tenant_id=2 AND id=?", t2.orderId()))
                .isEqualTo(1000L);
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE tenant_id=2 AND id=?", t2.entitlementId()))
                .isEqualTo(2);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE tenant_id=2 AND entitlement_id=? AND txn_type='REFUND_REVOKE'",
                t2.entitlementId())).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_idempotency_record WHERE tenant_id=2 AND scope_code='refund.provider_callback' AND actor_user_id=0 AND idempotency_key=?",
                cbKey)).isEqualTo(1);
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE tenant_id=1 AND id=?", t1Paid.orderId())).isZero();

        mockMvc.perform(callback(raw, cbKey, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refund.status").value("SUCCEEDED"));

        String sharedExt = key("shared-ext");
        Tenant2RefundFixture t2Collision = seedTenant2ProcessingRefund("t2-col", 1000L, 3);
        jdbcTemplate.update("UPDATE crm_refund_payment SET external_refund_no=? WHERE tenant_id=2 AND refund_id=?",
                sharedExt, t2Collision.refundId());
        long t1CollisionEnt = entitlementIds(t1Paid.orderId()).get(0);
        long t1CollisionRefund = processingRefund(manager1, finance, t1Paid.orderId(), 1000L,
                List.of(item(t1CollisionEnt, 1, 1000L)), "MOCK", sharedExt);

        mockMvc.perform(callback(callbackJson(sharedExt, 1000L, "SUCCEEDED", "COLLISION"), key("cb-col"), true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(queryString("SELECT status FROM crm_refund WHERE id=?", t1CollisionRefund)).isEqualTo("PROCESSING");
        assertThat(queryString("SELECT status FROM crm_refund WHERE id=?", t2Collision.refundId())).isEqualTo("PROCESSING");
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", t1Paid.orderId())).isZero();
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", t2Collision.orderId())).isZero();
    }

    @Test
    void hBiz03ConcurrentCreateRejectsOverReservationAndAllowsValidSplit() throws Exception {
        PaidOrder raceOrder = paidOrder(301, 1000L);
        long raceEnt = entitlementIds(raceOrder.orderId()).get(0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String k = key("race-create-" + i);
                futures.add(executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(createRequest(manager1, raceOrder.orderId(), 600L,
                                    List.of(item(raceEnt, 2, 600L)), k, "RACE"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(15, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(queryLong("SELECT COALESCE(SUM(requested_fen),0) FROM crm_refund WHERE order_id=?",
                    raceOrder.orderId())).isEqualTo(600L);
        } finally {
            executor.shutdownNow();
        }

        PaidOrder splitOrder = paidOrder(301, 1000L);
        long splitEnt = entitlementIds(splitOrder.orderId()).get(0);
        long r1 = data(createRequest(manager1, splitOrder.orderId(), 400L,
                List.of(item(splitEnt, 1, 400L)), key("split-c1"), "SPLIT-1")).at("/refund/id").asLong();
        long r2 = data(createRequest(manager1, splitOrder.orderId(), 600L,
                List.of(item(splitEnt, 2, 600L)), key("split-c2"), "SPLIT-2")).at("/refund/id").asLong();
        mockMvc.perform(createRequest(manager1, splitOrder.orderId(), 1L,
                        List.of(item(splitEnt, 1, 1L)), key("split-over"), "OVER"))
                .andExpect(status().isConflict());

        data(reviewRequest(finance, r1, 0, 400L, key("split-rev1")));
        data(reviewRequest(finance, r2, 0, 600L, key("split-rev2")));
        String ext1 = key("split-ext1");
        String ext2 = key("split-ext2");
        data(executeRequest(finance, r1, 1, "BANK_TRANSFER", ext1, 400L));
        data(executeRequest(finance, r2, 1, "BANK_TRANSFER", ext2, 600L));
        data(successRequest(finance, r1, manualRequest(ext1, 400L, file("CLEAN"), "SPLIT-S1"), key("split-s1")));
        data(successRequest(finance, r2, manualRequest(ext2, 600L, file("CLEAN"), "SPLIT-S2"), key("split-s2")));

        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", splitOrder.orderId())).isEqualTo(1000L);
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", splitEnt)).isZero();
        assertThat(queryString("SELECT status FROM crm_entitlement WHERE id=?", splitEnt)).isEqualTo("REVOKED");
    }

    @Test
    void hBiz03ExecuteBlockedAfterRedemptionAndFailedRetryBlockedByConcurrentRefund() throws Exception {
        PaidOrder redeemRace = paidOrder(301, 1000L);
        long redeemEnt = entitlementIds(redeemRace.orderId()).get(0);
        long r1 = data(createRequest(manager1, redeemRace.orderId(), 1000L,
                List.of(item(redeemEnt, 3, 1000L)), key("exec-block-c"), "EXEC-BLOCK"))
                .at("/refund/id").asLong();
        data(reviewRequest(finance, r1, 0, 1000L, key("exec-block-rev")));

        redeemEntitlement(redeemRace.customerId(), redeemEnt, 1);

        mockMvc.perform(executeRequest(finance, r1, 1, "MOCK", key("exec-block-ext"), 1000L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(queryString("SELECT status FROM crm_refund WHERE id=?", r1)).isEqualTo("APPROVED");

        PaidOrder retryOrder = paidOrder(301, 1000L);
        long retryEnt = entitlementIds(retryOrder.orderId()).get(0);
        String failedExt = key("retry-fail-ext");
        long failedRefund = processingRefund(manager1, finance, retryOrder.orderId(), 1000L,
                List.of(item(retryEnt, 3, 1000L)), "MOCK", failedExt);
        data(callback(callbackJson(failedExt, 1000L, "FAILED", "FAIL-1"), key("retry-fail-cb"), true));
        assertThat(queryString("SELECT status FROM crm_refund WHERE id=?", failedRefund)).isEqualTo("FAILED");

        long blockingRefund = data(createRequest(manager1, retryOrder.orderId(), 1000L,
                List.of(item(retryEnt, 3, 1000L)), key("retry-block-c2"), "BLOCKING"))
                .at("/refund/id").asLong();
        mockMvc.perform(executeRequest(finance, failedRefund, 3, "MOCK", key("retry-blocked-ext"), 1000L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(queryString("SELECT status FROM crm_refund WHERE id=?", failedRefund)).isEqualTo("FAILED");

        data(rejectRequest(finance, blockingRefund, 0, key("retry-reject-c2")));
        String okExt = key("retry-ok-ext");
        data(executeRequest(finance, failedRefund, 3, "MOCK", okExt, 1000L));
        data(callback(callbackJson(okExt, 1000L, "SUCCEEDED", "RETRY-OK"), key("retry-ok-cb"), true));
        assertThat(queryString("SELECT status FROM crm_refund WHERE id=?", failedRefund)).isEqualTo("SUCCEEDED");
    }

    @Test
    void mBe07PartialRefundRecalculatesOrderStatusPreservesExpiredAndReallocatesPartialApproval() throws Exception {
        PaidOrder statusOrder = paidOrder(301, 1000L);
        long statusEnt = entitlementIds(statusOrder.orderId()).get(0);
        redeemEntitlement(statusOrder.customerId(), statusEnt, 1);
        assertThat(queryString("SELECT status FROM crm_order WHERE id=?", statusOrder.orderId()))
                .isEqualTo("PARTIALLY_REDEEMED");

        long statusRefund = processingRefund(manager1, finance, statusOrder.orderId(), 600L,
                List.of(item(statusEnt, 2, 600L)), "BANK_TRANSFER", key("status-ext"));
        String statusExt = queryString("SELECT external_refund_no FROM crm_refund_payment WHERE refund_id=?", statusRefund);
        data(successRequest(finance, statusRefund, manualRequest(statusExt, 600L, file("CLEAN"), "MBE07-FULL"),
                key("status-succ")));
        assertThat(queryString("SELECT status FROM crm_order WHERE id=?", statusOrder.orderId())).isEqualTo("FULFILLED");
        assertThat(queryInt("SELECT COUNT(*) FROM crm_order_status_history WHERE order_id=? AND trigger_type='REFUND_SUCCEEDED' AND to_status='FULFILLED'",
                statusOrder.orderId())).isEqualTo(1);

        PaidOrder expiredOrder = paidOrder(multiProjectSku, 1000L);
        List<Long> expEnts = entitlementIds(expiredOrder.orderId());
        jdbcTemplate.update("UPDATE crm_entitlement SET status='EXPIRED' WHERE id=?", expEnts.get(0));
        jdbcTemplate.update("UPDATE crm_entitlement SET valid_to='2020-01-01' WHERE id=?", expEnts.get(1));
        long expRefund = processingRefund(manager1, finance, expiredOrder.orderId(), 500L,
                List.of(item(expEnts.get(0), 1, 200L), item(expEnts.get(1), 1, 300L)),
                "BANK_TRANSFER", key("exp-ext"));
        String expExt = queryString("SELECT external_refund_no FROM crm_refund_payment WHERE refund_id=?", expRefund);
        data(successRequest(finance, expRefund, manualRequest(expExt, 500L, file("CLEAN"), "MBE07-EXP"),
                key("exp-succ")));
        assertThat(queryString("SELECT status FROM crm_entitlement WHERE id=?", expEnts.get(0))).isEqualTo("EXPIRED");
        assertThat(queryString("SELECT status FROM crm_entitlement WHERE id=?", expEnts.get(1))).isEqualTo("EXPIRED");
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", expEnts.get(0))).isEqualTo(1);
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", expEnts.get(1))).isEqualTo(2);

        PaidOrder partialOrder = paidOrder(multiProjectSku, 1000L);
        List<Long> partEnts = entitlementIds(partialOrder.orderId());
        long partRefund = data(createRequest(manager1, partialOrder.orderId(), 1000L,
                List.of(item(partEnts.get(0), 1, 400L), item(partEnts.get(1), 1, 600L)),
                key("part-c"), "PARTIAL-APP")).at("/refund/id").asLong();
        data(reviewRequest(finance, partRefund, 0, 500L, key("part-rev")));
        String partExt = key("part-ext");
        data(executeRequest(finance, partRefund, 1, "BANK_TRANSFER", partExt, 500L));
        data(successRequest(finance, partRefund, manualRequest(partExt, 500L, file("CLEAN"), "MBE07-PART"),
                key("part-succ")));
        assertThat(queryLong("SELECT allocated_fen FROM crm_refund_item WHERE refund_id=? AND entitlement_id=?",
                partRefund, partEnts.get(0))).isEqualTo(200L);
        assertThat(queryLong("SELECT allocated_fen FROM crm_refund_item WHERE refund_id=? AND entitlement_id=?",
                partRefund, partEnts.get(1))).isEqualTo(300L);
        JsonNode reconciliation = data(finance.withAuth(get("/api/v1/settlements/reconciliation")));
        for (JsonNode issue : reconciliation.at("/issues")) {
            if ("REFUND_ALLOCATION".equals(issue.at("/checkType").asText())) {
                assertThat(issue.at("/resourceId").asLong()).isNotEqualTo(partRefund);
            }
        }
    }

    @Test
    void hBiz04ConcurrentMultiEntitlementRefundRedemptionAndMergeGuardHaveZeroDeadlocks() throws Exception {
        for (int round = 0; round < 10; round++) {
            final int roundIndex = round;
            PaidOrder paid = paidOrder(multiProjectSku, 1000L);
            List<Long> ents = entitlementIds(paid.orderId());
            long refundId = processingRefund(manager1, finance, paid.orderId(), 1000L,
                    List.of(item(ents.get(0), 1, 400L), item(ents.get(1), 1, 600L)),
                    "BANK_TRANSFER", key("hbiz04-ext-" + roundIndex));
            String ext = queryString("SELECT external_refund_no FROM crm_refund_payment WHERE refund_id=?", refundId);
            long cleanFile = file("CLEAN");
            ManualRefundSuccessRequest req = manualRequest(ext, 1000L, cleanFile, "HBIZ04-" + roundIndex);

            long apptId = createServiceDoneAppointment(paid.customerId(), List.of(
                    Map.of("entitlementId", ents.get(0), "count", 1),
                    Map.of("entitlementId", ents.get(1), "count", 2)));
            long primaryCustomer = createCustomer(admin, "IT-MERGE-PRI-" + roundIndex + "-" + UUID.randomUUID(), 2);
            long mergeCaseId = createAndReviewMergeCase(primaryCustomer, paid.customerId());

            ExecutorService executor = Executors.newFixedThreadPool(3);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<MvcResult> refundFuture = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(successRequest(finance, refundId, req, key("hbiz04-ref-" + roundIndex)))
                            .andReturn();
                });
                Future<MvcResult> redeemFuture = executor.submit(() -> {
                    start.await();
                    Map<String, Object> redeemBody = Map.of("appointmentVersion", 3L,
                            "serviceAt", Instant.now().toString(),
                            "evidenceRef", "HBIZ04-RED-" + roundIndex,
                            "lines", List.of(
                                    Map.of("entitlementId", ents.get(0), "count", 1),
                                    Map.of("entitlementId", ents.get(1), "count", 2)));
                    return mockMvc.perform(operator1.withAuth(post("/api/v1/appointments/" + apptId + "/redemptions"))
                            .header("Idempotency-Key", key("hbiz04-red-" + roundIndex))
                            .contentType(MediaType.APPLICATION_JSON).content(json(redeemBody))).andReturn();
                });
                Future<MvcResult> mergeFuture = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + mergeCaseId + "/confirm"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":1,\"reason\":\"并发校验\"}"))
                            .andReturn();
                });
                start.countDown();
                assertThat(refundFuture.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
                assertThat(redeemFuture.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
                assertThat(mergeFuture.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
                assertThat(queryString("SELECT status FROM crm_order WHERE id=?", paid.orderId()))
                        .isEqualTo("FULFILLED");
                assertThat(queryInt("SELECT deleted FROM crm_customer WHERE id=?", paid.customerId())).isZero();
            } finally {
                executor.shutdownNow();
            }
        }

        CurrentUser adminUser = loadUser("admin");
        CurrentUser financeUser = loadUser("finance1");
        CurrentUser operatorUser = loadUser("operator1");
        for (int round = 0; round < 10; round++) {
            final int roundIndex = round;
            boolean fullRevokeFirstEnt = (round % 2) == 1;
            PaidOrder paid = paidOrder(multiProjectSku, 1000L);
            List<Long> ents = entitlementIds(paid.orderId());
            List<Map<String, Object>> refundItems = fullRevokeFirstEnt
                    ? List.of(item(ents.get(0), 2, 400L))
                    : List.of(item(ents.get(0), 1, 400L), item(ents.get(1), 1, 600L));
            long refundAmount = fullRevokeFirstEnt ? 400L : 1000L;
            long refundId = processingRefund(manager1, finance, paid.orderId(), refundAmount,
                    refundItems, "BANK_TRANSFER", key("hbiz04-svc-ext-" + roundIndex));
            String ext = queryString("SELECT external_refund_no FROM crm_refund_payment WHERE refund_id=?", refundId);
            long cleanFile = file("CLEAN");
            ManualRefundSuccessRequest req = manualRequest(ext, refundAmount, cleanFile, "HBIZ04-SVC-" + roundIndex);

            List<Map<String, Object>> apptItems = fullRevokeFirstEnt
                    ? List.of(Map.of("entitlementId", ents.get(1), "count", 3))
                    : List.of(Map.of("entitlementId", ents.get(0), "count", 1),
                            Map.of("entitlementId", ents.get(1), "count", 2));
            List<RedemptionLineRequest> redeemLines = fullRevokeFirstEnt
                    ? List.of(new RedemptionLineRequest(ents.get(1), 3))
                    : List.of(new RedemptionLineRequest(ents.get(0), 1),
                            new RedemptionLineRequest(ents.get(1), 2));
            long apptId = createServiceDoneAppointment(paid.customerId(), apptItems);
            long primaryCustomer = createCustomer(admin, "IT-SVC-PRI-" + roundIndex + "-" + UUID.randomUUID(), 2);
            long mergeCaseId = createAndReviewMergeCase(primaryCustomer, paid.customerId());

            ExecutorService executor = Executors.newFixedThreadPool(3);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<?> refundFuture = executor.submit(() -> {
                    start.await();
                    return refundService.succeedManually(1L, financeUser, refundId, req,
                            key("hbiz04-svc-ref-" + roundIndex));
                });
                Future<?> redeemFuture = executor.submit(() -> {
                    start.await();
                    return redemptionService.post(1L, operatorUser, apptId,
                            new PostRedemptionRequest(3L, Instant.now(), "HBIZ04-SVC-RED-" + roundIndex,
                                    null, redeemLines),
                            key("hbiz04-svc-red-" + roundIndex));
                });
                Future<?> mergeFuture = executor.submit(() -> {
                    start.await();
                    return mergeService.confirm(1L, adminUser, mergeCaseId, 1L, "直调并发校验");
                });
                start.countDown();
                refundFuture.get(20, TimeUnit.SECONDS);
                redeemFuture.get(20, TimeUnit.SECONDS);
                assertThatThrownBy(() -> mergeFuture.get(20, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex.getCause()).errorCode())
                                .isEqualTo(ErrorCode.CONFLICT));
                assertThat(queryString("SELECT status FROM crm_order WHERE id=?", paid.orderId()))
                        .isEqualTo("FULFILLED");
                if (fullRevokeFirstEnt) {
                    assertThat(queryString("SELECT status FROM crm_entitlement WHERE id=?", ents.get(0)))
                            .isEqualTo("REVOKED");
                }
                assertThat(queryString("SELECT status FROM crm_entitlement WHERE id=?", ents.get(1)))
                        .isEqualTo("EXHAUSTED");
                assertThat(queryInt("SELECT deleted FROM crm_customer WHERE id=?", paid.customerId())).isZero();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void crossProviderRetryWithSameExternalRefundNoSucceedsAndRejectsStaleMockCallback() throws Exception {
        PaidOrder paid = paidOrder(301, 181100);
        long entitlementId = entitlementIds(paid.orderId()).get(0);
        String sharedExternal = key("cross-provider-ext");
        long refundId = processingRefund(manager1, finance, paid.orderId(), 1000L,
                List.of(item(entitlementId, 1, 1000L)), "MOCK", sharedExternal);

        data(callback(callbackJson(sharedExternal, 1000L, "FAILED", "MOCK-FAIL"), key("cross-cb-fail"), true));
        assertThat(queryString("SELECT status FROM crm_refund WHERE id=?", refundId)).isEqualTo("FAILED");

        data(executeRequest(finance, refundId, 3L, "BANK_TRANSFER", sharedExternal, 1000L));
        assertThat(queryString("SELECT status FROM crm_refund WHERE id=?", refundId)).isEqualTo("PROCESSING");
        assertThat(queryInt("SELECT COUNT(*) FROM crm_refund_payment WHERE refund_id=? AND external_refund_no=?",
                refundId, sharedExternal)).isEqualTo(2);

        mockMvc.perform(callback(callbackJson(sharedExternal, 1000L, "SUCCEEDED", "STALE-MOCK-OK"),
                        key("cross-cb-stale"), true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);

        long cleanFile = file("CLEAN");
        ManualRefundSuccessRequest req = manualRequest(sharedExternal, 1000L, cleanFile, "BANK-OK");
        data(successRequest(finance, refundId, req, key("cross-manual-succ")));
        data(successRequest(finance, refundId, req, key("cross-manual-replay")));

        assertThat(queryString("SELECT status FROM crm_refund WHERE id=?", refundId)).isEqualTo("SUCCEEDED");
        assertThat(queryString("SELECT status FROM crm_refund_payment WHERE refund_id=? AND provider='MOCK'", refundId))
                .isEqualTo("FAILED");
        assertThat(queryString("SELECT status FROM crm_refund_payment WHERE refund_id=? AND provider='BANK_TRANSFER'", refundId))
                .isEqualTo("SUCCEEDED");
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", paid.orderId())).isEqualTo(1000L);
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", entitlementId)).isEqualTo(2);
    }

    private CurrentUser loadUser(String username) {
        long userId = queryLong("SELECT id FROM sys_user WHERE tenant_id=1 AND username=?", username);
        return currentUserService.loadById(userId).orElseThrow();
    }

    private long processingRefund(AuthCookies creator, AuthCookies finance, long orderId, long amount,
                                  List<Map<String, Object>> items, String provider, String external) throws Exception {
        long id = data(createRequest(creator, orderId, amount, items, key("flow-create"), "FLOW"))
                .at("/refund/id").asLong();
        data(reviewRequest(finance, id, 0, amount, key("flow-review")));
        data(executeRequest(finance, id, 1, provider, external, amount));
        return id;
    }

    private MockHttpServletRequestBuilder createRequest(AuthCookies actor, long orderId, long amount,
                                                         List<Map<String, Object>> items, String key, String reason) throws Exception {
        Map<String, Object> body = Map.of("orderId", orderId, "requestedFen", amount, "reasonCode", "CUSTOMER_REQUEST",
                "reasonDetail", reason, "items", items);
        return actor.withAuth(post("/api/v1/refunds")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private MockHttpServletRequestBuilder reviewRequest(AuthCookies actor, long refundId, long version,
                                                         long amount, String key) throws Exception {
        Map<String, Object> body = Map.of("version", version, "decision", "APPROVED", "approvedFen", amount, "reason", "IT-APPROVE");
        return actor.withAuth(post("/api/v1/refunds/" + refundId + "/review")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private MockHttpServletRequestBuilder rejectRequest(AuthCookies actor, long refundId, long version,
                                                         String key) throws Exception {
        Map<String, Object> body = Map.of("version", version, "decision", "REJECTED", "reason", "IT-REJECT");
        return actor.withAuth(post("/api/v1/refunds/" + refundId + "/review")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private void redeemEntitlement(long customerId, long entitlementId, int count) throws Exception {
        long apptId = createServiceDoneAppointment(customerId, List.of(Map.of("entitlementId", entitlementId, "count", count)));
        Map<String, Object> redeemBody = Map.of("appointmentVersion", 3L, "serviceAt", Instant.now().toString(),
                "evidenceRef", "IT-REDEEM", "lines", List.of(Map.of("entitlementId", entitlementId, "count", count)));
        data(operator1.withAuth(post("/api/v1/appointments/" + apptId + "/redemptions"))
                .header("Idempotency-Key", key("redeem"))
                .contentType(MediaType.APPLICATION_JSON).content(json(redeemBody)));
    }

    private long createServiceDoneAppointment(long customerId, List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = Map.of("customerId", customerId, "institutionId", 1,
                "scheduledAt", Instant.now().plusSeconds(86400).toString(), "remark", "IT-REFUND-FUL", "items", items);
        long apptId = data(manager1.withAuth(post("/api/v1/appointments"))
                .header("Idempotency-Key", key("appt"))
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).at("/appointment/id").asLong();
        data(operator1.withAuth(post("/api/v1/appointments/" + apptId + "/confirm"))
                .header("Idempotency-Key", key("confirm"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"IT\"}"));
        data(operator1.withAuth(post("/api/v1/appointments/" + apptId + "/check-in"))
                .header("Idempotency-Key", key("checkin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"reason\":\"IT\"}"));
        data(operator1.withAuth(post("/api/v1/appointments/" + apptId + "/service-done"))
                .header("Idempotency-Key", key("done"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2,\"reason\":\"IT\"}"));
        return apptId;
    }

    private long createAndReviewMergeCase(long primary, long secondary) throws Exception {
        Map<String, Object> body = Map.of("primaryCustomerId", primary, "secondaryCustomerId", secondary,
                "reason", "并发合并测试");
        long caseId = data(admin.withAuth(post("/api/v1/customer-merges"))
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).at("/id").asLong();
        data(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/review"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));
        return caseId;
    }

    private Tenant2RefundFixture seedTenant2ProcessingRefund(String prefix, long amountFen, int count) {
        String suffix = prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Instant now = Instant.parse("2026-10-20T08:00:00Z");
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,customer_no,nickname) VALUES (2,1,?,?)",
                "C2-" + suffix, "租户2客户");
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=2 AND customer_no=?", "C2-" + suffix);
        String projCode = "PRJ2-" + suffix;
        catalogRepository.insertProject(2L, new Project(null, projCode, "租户2项目", "CAT", amountFen, "ACTIVE", null, null));
        long projId = catalogRepository.findProjectByCode(2L, projCode).orElseThrow().id();
        String pkgCode = "P2-" + suffix;
        catalogRepository.insertPackage(2L, new ProductPackage(null, pkgCode, "租户2套餐", "ACTIVE", null, null));
        long pkgId = catalogRepository.findPackageByCode(2L, pkgCode).orElseThrow().id();
        catalogRepository.insertVersion(2L, new PackageVersion(null, pkgId, 1, "FIXED", 0, null, "ACTIVE", null, null));
        long verId = catalogRepository.findVersions(2L, pkgId).get(0).id();
        String skuCode = "SKU2-" + suffix;
        catalogRepository.insertSku(2L, new Sku(null, skuCode, verId, null, null, null, null, "标准", amountFen,
                "ACTIVE", null, null, null, null));
        long skuId = catalogRepository.findSkuByCode(2L, skuCode).orElseThrow().id();
        String orderNo = "ORD2-" + suffix;
        tradingRepository.insertOrder(2L, new Order(null, orderNo, customerId, null, 1L, 1L, null, "CRM", null,
                "REGULAR", "常规订单", null, null, null, null, null, null, null, null, "PAID", "PLATFORM",
                amountFen, 0L, amountFen, amountFen, 0L, now, now, null, null));
        long orderId = tradingRepository.findOrderByNo(2L, orderNo).orElseThrow().id();
        tradingRepository.insertOrderItem(2L, new OrderItem(null, orderId, skuId, verId, skuCode, "标准",
                1, amountFen, amountFen, "{}", null));
        long orderItemId = tradingRepository.findOrderItems(2L, orderId).get(0).id();
        tradingRepository.insertEntitlement(2L, new Entitlement(null, customerId, orderItemId, projId, null,
                count, count, 1, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "AVAILABLE", null, null));
        long entId = tradingRepository.findEntitlementsByOrderItemIds(2L, List.of(orderItemId)).get(0).id();
        tradingRepository.insertLedger(2L, new EntitlementLedgerEntry(null, entId, "GRANT", count, "ORDER",
                Long.toString(orderId), "t2-grant-" + suffix, 1L, "grant", null, null));
        String refundNo = "RF2-" + suffix;
        refundRepository.insertRefund(2L, new Refund(null, 2L, refundNo, orderId, customerId, amountFen, 0L, 0L,
                "CUSTOMER_REQUEST", "t2", "REQUESTED", 1L, null, null, null, null, null, null));
        long refundId = refundRepository.findByNo(2L, refundNo).orElseThrow().id();
        refundRepository.updateStatus(2L, refundId, 0L, "REQUESTED", "APPROVED", amountFen, null, 2L, null, null);
        refundRepository.updateStatus(2L, refundId, 1L, "APPROVED", "PROCESSING", null, null, null, 2L, null);
        refundRepository.insertItem(2L, new RefundItem(null, 2L, refundId, entId, 1, amountFen));
        String extNo = "T2-EXT-" + suffix;
        refundRepository.insertPayment(2L, new RefundPayment(null, 2L, refundId, "MOCK", extNo, amountFen, "PENDING",
                "PROVIDER_CALLBACK", "T2-EXEC", null, null, null, null, null));
        return new Tenant2RefundFixture(orderId, entId, refundId, extNo);
    }

    private MockHttpServletRequestBuilder executeRequest(AuthCookies actor, long refundId, long version, String provider,
                                                          String external, long amount) throws Exception {
        Map<String, Object> body = Map.of("version", version, "provider", provider, "externalRefundNo", external,
                "amountFen", amount, "evidenceRef", "IT-EXECUTE");
        return actor.withAuth(post("/api/v1/refunds/" + refundId + "/executions")).header("Idempotency-Key", key("execute"))
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private MockHttpServletRequestBuilder successRequest(AuthCookies actor, long refundId,
                                                          ManualRefundSuccessRequest body, String key) throws Exception {
        return actor.withAuth(post("/api/v1/refunds/" + refundId + "/success")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private ManualRefundSuccessRequest manualRequest(String external, long amount, long fileId, String evidence) {
        return new ManualRefundSuccessRequest(external, amount, Instant.now().minusSeconds(10), evidence, fileId);
    }

    private MockHttpServletRequestBuilder callback(String raw, String key, boolean validSignature) {
        long timestamp = Instant.now().getEpochSecond();
        String signature = validSignature ? mockAdapter.sign(raw, timestamp) : "missing";
        return post("/api/v1/integrations/refunds/MOCK/callback")
                .header("Idempotency-Key", key)
                .header("X-Refund-Mock-Timestamp", timestamp)
                .header("X-Refund-Mock-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).content(raw);
    }

    private String callbackJson(String external, long amount, String status, String evidence) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("externalRefundNo", external);
        payload.put("amountFen", amount);
        payload.put("status", status);
        payload.put("occurredAt", Instant.now().minusSeconds(5).toString());
        if (evidence != null) {
            payload.put("evidenceRef", evidence);
        }
        return json(payload);
    }

    private PaidOrder paidOrder(long skuId, long amount) throws Exception {
        long customerId = createCustomer(admin, "IT-REFUND-" + UUID.randomUUID(), 2);
        long orderId = createOrder(admin, customerId, skuId, amount);
        submitOrder(admin, orderId);
        confirmPayment(admin, orderId, key("pay"), amount);
        return new PaidOrder(customerId, orderId);
    }

    private long createOrder(AuthCookies actor, long customerId, long skuId, long amount) throws Exception {
        Map<String, Object> body = Map.of("customerId", customerId, "receiverType", "PLATFORM", "orderTypeCode", "REGULAR",
                "campaignId", 1, "campaignBatchId", 1, "payableFen", amount, "validTo", "2027-12-31",
                "items", List.of(Map.of("skuId", skuId, "quantity", 1)));
        return data(actor.withAuth(post("/api/v1/orders")).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .at("/order/order/id").asLong();
    }

    private void submitOrder(AuthCookies actor, long orderId) throws Exception {
        data(actor.withAuth(post("/api/v1/orders/" + orderId + "/submit"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));
    }

    private void confirmPayment(AuthCookies actor, long orderId, String tradeNo, long amount) throws Exception {
        String body = "{\"externalTradeNo\":\"" + tradeNo + "\",\"paymentChannel\":\"BANK_TRANSFER\","
                + "\"amountFen\":" + amount + ",\"paidAt\":\"2026-10-24T03:00:00Z\",\"evidenceRef\":\"IT-PAID\"}";
        data(actor.withAuth(post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", tradeNo).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long createCustomer(AuthCookies actor, String nickname, long ownerId) throws Exception {
        Map<String, Object> body = Map.of("nickname", nickname, "ownerStaffId", ownerId);
        return data(actor.withAuth(post("/api/v1/customers")).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .at("/id").asLong();
    }

    private long createTwoProjectSku() throws Exception {
        Map<String, Object> versionBody = Map.of("selectionMode", "FIXED", "choiceCount", 0,
                "projects", List.of(Map.of("projectId", 101, "grantCount", 2, "allocationPriceFen", 400),
                        Map.of("projectId", 102, "grantCount", 3, "allocationPriceFen", 600)));
        long versionId = data(admin.withAuth(post("/api/v1/packages/201/versions"))
                .contentType(MediaType.APPLICATION_JSON).content(json(versionBody))).at("/id").asLong();
        data(admin.withAuth(post("/api/v1/package-versions/" + versionId + "/activate"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));
        Map<String, Object> skuBody = Map.of("skuCode", "IT-REFUND-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "packageVersionId", versionId, "specName", "退款双权益", "salePriceFen", 1000, "institutionIds", List.of(1));
        long skuId = data(admin.withAuth(post("/api/v1/skus")).contentType(MediaType.APPLICATION_JSON).content(json(skuBody)))
                .at("/id").asLong();
        data(admin.withAuth(post("/api/v1/skus/" + skuId + "/activate"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));
        return skuId;
    }

    private long file(String scanStatus) {
        String objectKey = "sealed/1/it-refund-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO crm_file_object (tenant_id,object_key,mime_type,size_bytes,sha256,classification,scan_status,uploaded_by) VALUES (1,?,'application/pdf',1,?,'PAYMENT',?,1)",
                objectKey, UUID.randomUUID().toString().replace("-", "").repeat(2), scanStatus);
        return queryLong("SELECT id FROM crm_file_object WHERE object_key=?", objectKey);
    }

    private List<Long> entitlementIds(long orderId) {
        return jdbcTemplate.queryForList("SELECT e.id FROM crm_entitlement e JOIN crm_order_item oi ON oi.id=e.order_item_id WHERE oi.order_id=? ORDER BY e.id", Long.class, orderId);
    }

    private Map<String, Object> item(long entitlementId, int count, long amount) {
        return Map.of("entitlementId", entitlementId, "revokeCount", count, "allocatedFen", amount);
    }

    private void assertUnchanged(long orderId, long entitlementId, int balance, long refundId) {
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", orderId)).isZero();
        assertThat(queryLong("SELECT succeeded_fen FROM crm_refund WHERE id=?", refundId)).isZero();
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", entitlementId)).isEqualTo(balance);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type='REFUND_REVOKE'", entitlementId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?", "refund.succeeded.v1:" + refundId)).isZero();
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
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

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return "it-refund-" + prefix + "-" + UUID.randomUUID();
    }

    private record PaidOrder(long customerId, long orderId) { }

    private record Tenant2RefundFixture(long orderId, long entitlementId, long refundId, String externalRefundNo) { }
}
