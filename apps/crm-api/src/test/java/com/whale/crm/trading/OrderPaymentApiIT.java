package com.whale.crm.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.trading.api.dto.PaymentConfirmRequest;
import com.whale.crm.trading.application.EntitlementApplicationService;
import com.whale.crm.trading.application.PaymentApplicationService;
import com.whale.crm.trading.domain.EntitlementDetail;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderPaymentApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PaymentApplicationService paymentService;
    @Autowired
    private EntitlementApplicationService entitlementService;
    @Autowired
    private CurrentUserService currentUserService;

    @Test
    void o01_draftAndUnverifiedPaymentCannotGrantEntitlements() throws Exception {
        AuthCookies admin = login("admin");
        long customerId = createCustomer(admin, "ITO01-未支付");
        long orderId = createOrder(admin, customerId, 301, 1, 181100);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_entitlement WHERE order_item_id IN (SELECT id FROM crm_order_item WHERE order_id=?)", Integer.class, orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id IN (SELECT id FROM crm_entitlement WHERE order_item_id IN (SELECT id FROM crm_order_item WHERE order_id=?))", Integer.class, orderId)).isZero();

        MockHttpServletRequestBuilder draftPaymentRequest = admin.withAuth(
                        post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", "ITO01-DRAFT")
                .contentType(MediaType.APPLICATION_JSON).content(paymentJson("ITO01-DRAFT", 181100));
        mockMvc.perform(draftPaymentRequest)
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_payment_txn WHERE order_id=?", Integer.class, orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_entitlement WHERE order_item_id IN (SELECT id FROM crm_order_item WHERE order_id=?)", Integer.class, orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?", Integer.class,
                "order.paid.v1:" + orderId)).isZero();
        mockMvc.perform(get("/api/v1/entitlements?customerId=" + customerId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void paymentConfirmIsAtomicAndReplayReturnsFirstResult() throws Exception {
        AuthCookies admin = login("admin");
        long customerId = createCustomer(admin, "ITO02-支付");
        long orderId = createOrder(admin, customerId, 301, 2, 362200);
        submitOrder(admin, orderId);
        String tradeNo = "ITO02-PAY-001";
        MockHttpServletRequestBuilder paymentRequest = admin.withAuth(
                        post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", tradeNo)
                .contentType(MediaType.APPLICATION_JSON).content(paymentJson(tradeNo, 362200));
        mockMvc.perform(paymentRequest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.order.status").value("PAID"))
                .andExpect(jsonPath("$.data.order.order.paidFen").value(362200))
                .andExpect(jsonPath("$.data.order.payments[0].verificationMethod").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.data.entitlements.length()").value(1))
                .andExpect(jsonPath("$.data.entitlements[0].grantedCount").value(6))
                .andExpect(jsonPath("$.data.entitlements[0].availableCount").value(6));

        MockHttpServletRequestBuilder replayRequest = admin.withAuth(
                        post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", tradeNo)
                .contentType(MediaType.APPLICATION_JSON).content(paymentJson(tradeNo, 362200));
        mockMvc.perform(replayRequest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.payments.length()").value(1))
                .andExpect(jsonPath("$.data.entitlements.length()").value(1));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_payment_txn WHERE order_id=?", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE txn_type='GRANT' AND entitlement_id IN (SELECT id FROM crm_entitlement WHERE order_item_id IN (SELECT id FROM crm_order_item WHERE order_id=?))", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?", Integer.class,
                "order.paid.v1:" + orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order_status_history WHERE order_id=? AND to_status='PAID'",
                Integer.class, orderId)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstOrderAt").isNotEmpty());
        mockMvc.perform(get("/api/v1/customers/" + customerId + "/legacy-profile").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.fieldKey == 'ORDER_COUNT')].availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[?(@.fieldKey == 'ORDER_TOTAL_AMOUNT')].value").isNotEmpty());
    }

    @Test
    void o02_oneHundredConcurrentConfirmationsCreateOnlyOnePaymentEntitlementAndOutbox() throws Exception {
        AuthCookies admin = login("admin");
        long customerId = createCustomer(admin, "ITO02-并发");
        long orderId = createOrder(admin, customerId, 302, 1, 331100);
        submitOrder(admin, orderId);
        CurrentUser actor = currentUserService.loadById(1).orElseThrow();
        PaymentConfirmRequest request = new PaymentConfirmRequest("ITO02-CONCURRENT", "BANK_TRANSFER", 331100L,
                Instant.parse("2026-10-24T03:00:00Z"), "DEV-CONCURRENT-PROOF", null);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderFulfillmentView>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return paymentService.confirm(1L, actor, orderId, request, "ITO02-CONCURRENT");
                }));
            }
            start.countDown();
            for (Future<OrderFulfillmentView> future : futures) {
                assertThat(future.get().order().order().status()).isEqualTo("PAID");
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_payment_txn WHERE order_id=?", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_entitlement WHERE order_item_id IN (SELECT id FROM crm_order_item WHERE order_id=?)", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE txn_type='GRANT' AND entitlement_id IN (SELECT id FROM crm_entitlement WHERE order_item_id IN (SELECT id FROM crm_order_item WHERE order_id=?))", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?", Integer.class,
                "order.paid.v1:" + orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order_status_history WHERE order_id=? AND to_status='PAID'",
                Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void e01_purchaseTenRedeemSixLeavesFourAndE02ConcurrentRedemptionCannotGoNegative() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies operator = login("operator2");
        activateTenCountSku(admin);
        long firstCustomer = createCustomer(admin, "ITE01-余额");
        long firstOrder = createOrder(admin, firstCustomer, 303, 1, 499900);
        submitOrder(admin, firstOrder);
        confirmPayment(admin, firstOrder, "ITE01-PAY", 499900);
        Map<String, Object> firstPaymentFact = orderPaymentFact(firstOrder);
        CurrentUser actor = currentUserService.loadById(1).orElseThrow();
        long entitlementId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_entitlement WHERE order_item_id IN (SELECT id FROM crm_order_item WHERE order_id=?)",
                Long.class, firstOrder);
        PreparedRedemption first = prepareRedemption(admin, operator, firstCustomer, entitlementId, 6, "ITE01-KEY");
        PreparedRedemption early = new PreparedRedemption(first.appointmentId(), first.appointmentVersion(),
                first.entitlementId(), 6, Instant.now().minusSeconds(3600).toString(),
                "ITE01-EARLY-PROOF", "ITE01-EARLY");
        mockMvc.perform(redemptionRequest(operator, early, 6, early.evidenceRef(), early.idempotencyKey()))
                .andExpect(status().isConflict());
        PreparedRedemption future = new PreparedRedemption(first.appointmentId(), first.appointmentVersion(),
                first.entitlementId(), 6, Instant.now().plusSeconds(3600).toString(),
                "ITE01-FUTURE-PROOF", "ITE01-FUTURE");
        mockMvc.perform(redemptionRequest(operator, future, 6, future.evidenceRef(), future.idempotencyKey()))
                .andExpect(status().isBadRequest());
        assertThat(entitlementService.detail(1L, actor, entitlementId).availableBalance()).isEqualTo(10);
        long redemptionId = postRedemption(operator, first).at("/redemption/id").asLong();
        EntitlementDetail afterSix = entitlementService.detail(1L, actor, entitlementId);
        assertThat(afterSix.availableBalance()).isEqualTo(4);
        assertThat(afterSix.entitlement().availableCount()).isEqualTo(4);
        assertThat(afterSix.entitlement().status()).isEqualTo("AVAILABLE");
        long replayedId = postRedemption(operator, first).at("/redemption/id").asLong();
        assertThat(replayedId).isEqualTo(redemptionId);
        assertThat(entitlementService.detail(1L, actor, entitlementId).availableBalance()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type='REDEEM'",
                Integer.class, entitlementId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_order WHERE id=?", String.class, firstOrder))
                .isEqualTo("PARTIALLY_REDEEMED");
        assertPaymentFactUnchanged(firstOrder, firstPaymentFact);
        long redemptionLineId = jdbcTemplate.queryForObject("SELECT id FROM crm_redemption_line WHERE redemption_id=?",
                Long.class, redemptionId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT trigger_ref FROM crm_order_status_history WHERE order_id=? AND to_status='PARTIALLY_REDEEMED'",
                String.class, firstOrder)).isEqualTo(Long.toString(redemptionLineId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_order_status_history WHERE order_id=? AND to_status='PARTIALLY_REDEEMED'",
                Integer.class, firstOrder)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_outbox_event WHERE event_type='entitlement.redeemed.v1' AND aggregate_id=?",
                Integer.class, Long.toString(entitlementId))).isEqualTo(1);
        String redeemedEventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM crm_outbox_event WHERE event_type='entitlement.redeemed.v1' AND aggregate_id=?",
                String.class, Long.toString(entitlementId));
        JsonNode redeemedPayload = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT payload_json FROM crm_outbox_event WHERE event_id=?", String.class, redeemedEventId));
        assertThat(redeemedEventId).contains("redemption.post:");
        assertThat(redeemedPayload.at("/entitlementId").asLong()).isEqualTo(entitlementId);
        assertThat(redeemedPayload.at("/count").asInt()).isEqualTo(6);
        assertThat(redeemedPayload.at("/sourceRef").asText()).isEqualTo(Long.toString(redemptionLineId));
        mockMvc.perform(get("/api/v1/redemptions?customerId=" + firstCustomer + "&projectId=101")
                        .cookie(operator.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(redemptionId));
        mockMvc.perform(get("/api/v1/redemptions?customerId=" + firstCustomer + "&projectId=102")
                        .cookie(operator.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        AuthCookies otherOperator = login("operator1");
        mockMvc.perform(get("/api/v1/redemptions?customerId=" + firstCustomer + "&projectId=101")
                        .cookie(otherOperator.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(redemptionRequest(operator, first, 5, first.evidenceRef(), first.idempotencyKey()))
                .andExpect(status().isConflict());
        mockMvc.perform(redemptionRequest(operator, first, 6, "ITE01-CHANGED", first.idempotencyKey()))
                .andExpect(status().isConflict());

        AuthCookies finance = login("finance1");
        JsonNode requested = data(admin.withAuth(post("/api/v1/redemptions/" + redemptionId + "/reversal-requests"))
                .header("Idempotency-Key", "ITE01-REVERSAL-REQUEST")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("redemptionVersion", 0, "reason", "IT-REVERSE"))));
        long reversalId = requested.at("/reversal/id").asLong();
        assertThat(requested.at("/corrections")).isEmpty();
        Map<String, Object> reviewBody = Map.of("version", 0, "reason", "IT-APPROVE");
        JsonNode approved = data(finance.withAuth(post("/api/v1/redemption-reversals/" + reversalId + "/approve"))
                .header("Idempotency-Key", "ITE01-REVERSAL-APPROVE")
                .contentType(MediaType.APPLICATION_JSON).content(json(reviewBody)));
        assertThat(approved.at("/corrections")).hasSize(1);
        assertThat(approved.at("/corrections/0/ledgerId").asLong()).isPositive();
        assertThat(approved.at("/corrections/0/entitlementId").asLong()).isEqualTo(entitlementId);
        assertThat(approved.at("/corrections/0/projectId").asLong()).isEqualTo(101);
        assertThat(approved.at("/corrections/0/deltaCount").asInt()).isEqualTo(6);
        assertThat(approved.at("/corrections/0/sourceRef").asText()).isEqualTo(Long.toString(reversalId));
        JsonNode approvedReplay = data(finance.withAuth(post("/api/v1/redemption-reversals/" + reversalId + "/approve"))
                .header("Idempotency-Key", "ITE01-REVERSAL-APPROVE")
                .contentType(MediaType.APPLICATION_JSON).content(json(reviewBody)));
        assertThat(approvedReplay.at("/corrections")).hasSize(1);
        assertThat(entitlementService.detail(1L, actor, entitlementId).availableBalance()).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_order WHERE id=?", String.class, firstOrder))
                .isEqualTo("PAID");
        assertPaymentFactUnchanged(firstOrder, firstPaymentFact);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_redemption WHERE id=?", String.class, redemptionId))
                .isEqualTo("POSTED");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_redemption WHERE id=?", Long.class, redemptionId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_ref FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type='REDEEM'",
                String.class, entitlementId)).isEqualTo(Long.toString(redemptionLineId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type='CORRECTION_IN'",
                Integer.class, entitlementId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_order_status_history WHERE order_id=? AND trigger_type='REDEMPTION_REVERSED'",
                Integer.class, firstOrder)).isEqualTo(1);
        Map<String, Object> reversalHistory = jdbcTemplate.queryForMap(
                "SELECT trigger_ref,actor_user_id FROM crm_order_status_history WHERE order_id=? AND trigger_type='REDEMPTION_REVERSED'",
                firstOrder);
        assertThat(reversalHistory.get("trigger_ref")).isEqualTo(Long.toString(reversalId));
        assertThat(((Number) reversalHistory.get("actor_user_id")).longValue()).isEqualTo(5);
        JsonNode approvedPayload = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT payload_json FROM crm_outbox_event WHERE event_id=?", String.class,
                "redemption.reversal.approved.v1:" + reversalId));
        assertThat(approvedPayload.at("/corrections")).hasSize(1);
        assertThat(approvedPayload.at("/corrections/0/ledgerId").asLong()).isPositive();
        assertThat(approvedPayload.at("/corrections/0/entitlementId").asLong()).isEqualTo(entitlementId);
        assertThat(approvedPayload.at("/corrections/0/projectId").asLong()).isEqualTo(101);
        assertThat(approvedPayload.at("/corrections/0/deltaCount").asInt()).isEqualTo(6);
        assertThat(approvedPayload.at("/corrections/0/sourceRef").asText()).isEqualTo(Long.toString(reversalId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_outbox_event WHERE event_type='entitlement.redeemed.v1' AND aggregate_id=?",
                Integer.class, Long.toString(entitlementId))).isEqualTo(1);

        long secondCustomer = createCustomer(admin, "ITE02-一次");
        long secondOrder = createOrder(admin, secondCustomer, 303, 1, 499900);
        submitOrder(admin, secondOrder);
        confirmPayment(admin, secondOrder, "ITE02-PAY", 499900);
        Map<String, Object> secondPaymentFact = orderPaymentFact(secondOrder);
        long secondEntitlement = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_entitlement WHERE order_item_id IN (SELECT id FROM crm_order_item WHERE order_id=?)",
                Long.class, secondOrder);
        PreparedRedemption otherOrder = prepareRedemption(admin, operator, secondCustomer, secondEntitlement, 6, "ITE01-KEY");
        mockMvc.perform(redemptionRequest(operator, otherOrder, 6, otherOrder.evidenceRef(), otherOrder.idempotencyKey()))
                .andExpect(status().isConflict());
        PreparedRedemption nine = prepareRedemption(admin, operator, secondCustomer, secondEntitlement, 9, "ITE02-NINE");
        postRedemption(operator, nine);
        assertThat(entitlementService.detail(1L, actor, secondEntitlement).availableBalance()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_order WHERE id=?", String.class, secondOrder))
                .isEqualTo("PARTIALLY_REDEEMED");
        assertPaymentFactUnchanged(secondOrder, secondPaymentFact);

        PreparedRedemption e02a = prepareRedemption(admin, operator, secondCustomer, secondEntitlement, 1, "E02-A");
        PreparedRedemption e02b = prepareRedemption(admin, operator, secondCustomer, secondEntitlement, 1, "E02-B");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> firstResult = executor.submit(() -> {
                start.await();
                return mockMvc.perform(redemptionRequest(operator, e02a, 1, e02a.evidenceRef(), e02a.idempotencyKey()))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> secondResult = executor.submit(() -> {
                start.await();
                return mockMvc.perform(redemptionRequest(operator, e02b, 1, e02b.evidenceRef(), e02b.idempotencyKey()))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(firstResult.get(), secondResult.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }
        EntitlementDetail finalDetail = entitlementService.detail(1L, actor, secondEntitlement);
        assertThat(finalDetail.availableBalance()).isZero();
        assertThat(finalDetail.entitlement().availableCount()).isZero();
        assertThat(finalDetail.entitlement().status()).isEqualTo("EXHAUSTED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND delta_count<0",
                Integer.class, secondEntitlement)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_order WHERE id=?", String.class, secondOrder))
                .isEqualTo("FULFILLED");
        assertPaymentFactUnchanged(secondOrder, secondPaymentFact);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_order_status_history WHERE order_id=? AND to_status='PARTIALLY_REDEEMED'",
                Integer.class, secondOrder)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_order_status_history WHERE order_id=? AND to_status='FULFILLED'",
                Integer.class, secondOrder)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_outbox_event WHERE event_type='entitlement.redeemed.v1' AND aggregate_id=?",
                Integer.class, Long.toString(secondEntitlement))).isEqualTo(2);
    }

    @Test
    void orderReadAndPaymentPermissionAreEnforced() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        AuthCookies admin = login("admin");
        long customerId = createCustomer(manager1, "ITO03-范围", 2);
        long orderId = createOrder(manager1, customerId, 301, 1, 181100);
        submitOrder(manager1, orderId);
        mockMvc.perform(get("/api/v1/orders/" + orderId).cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/orders?customerId=" + customerId).cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        MockHttpServletRequestBuilder deniedPaymentRequest = manager1.withAuth(
                        post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", "ITO03-DENIED")
                .contentType(MediaType.APPLICATION_JSON).content(paymentJson("ITO03-DENIED", 181100));
        mockMvc.perform(deniedPaymentRequest)
                .andExpect(status().isForbidden());
        MockHttpServletRequestBuilder adminPaymentRequest = admin.withAuth(
                        post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", "ITO03-ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(paymentJson("ITO03-ADMIN", 181100));
        mockMvc.perform(adminPaymentRequest)
                .andExpect(status().isOk());
    }

    @Test
    void paymentConfirmationRequiresIdempotencyKey() throws Exception {
        AuthCookies admin = login("admin");
        long customerId = createCustomer(admin, "IT-IDEM-必传");
        long orderId = createOrder(admin, customerId, 301, 1, 181100);
        submitOrder(admin, orderId);

        mockMvc.perform(admin.withAuth(post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                        .contentType(MediaType.APPLICATION_JSON).content(paymentJson("IT-IDEM-MISSING", 181100)))
                .andExpect(status().isBadRequest());
        assertNoPaymentArtifacts(orderId);
    }

    @Test
    void paymentSameKeyWithDifferentEvidenceIs409AndKeepsFirstFacts() throws Exception {
        AuthCookies admin = login("admin");
        long customerId = createCustomer(admin, "IT-IDEM-凭证");
        long orderId = createOrder(admin, customerId, 301, 1, 181100);
        submitOrder(admin, orderId);
        String tradeNo = "IT-IDEM-EVIDENCE";
        confirmPayment(admin, orderId, tradeNo, 181100);

        MockHttpServletRequestBuilder changed = admin.withAuth(
                        post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", tradeNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentJson(tradeNo, 181100, "IT-DIFFERENT-EVIDENCE"));
        mockMvc.perform(changed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_payment_txn WHERE order_id=?",
                Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT evidence_ref FROM crm_payment_txn WHERE order_id=?",
                String.class, orderId)).isEqualTo("DEV-LOCAL-PROOF");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?",
                Integer.class, "order.paid.v1:" + orderId)).isEqualTo(1);
    }

    @Test
    void singleItemPendingQ02SavesZeroAndStillGrantsConfiguredCounts() throws Exception {
        AuthCookies admin = login("admin");
        long skuId = createTwoProjectSku(admin, "IT-Q02-PENDING", 0L, 0L);
        long customerId = createCustomer(admin, "IT-Q02-单行待分摊");
        long orderId = createOrder(admin, customerId, skuId, 1, 1000);

        JsonNode snapshot = orderSnapshot(orderId);
        assertThat(snapshot.path("allocationStatus").asText()).isEqualTo("PENDING_Q02");
        assertThat(snapshot.path("paidAllocatedFen").asLong()).isZero();
        assertThat(snapshot.path("projectSnapshots")).hasSize(2);
        snapshot.path("projectSnapshots").forEach(project ->
                assertThat(project.path("paidAllocatedFen").asLong()).isZero());
        assertThat(jdbcTemplate.queryForObject("SELECT paid_allocated_fen FROM crm_order_item WHERE order_id=?",
                Long.class, orderId)).isZero();

        submitOrder(admin, orderId);
        confirmPayment(admin, orderId, "IT-Q02-PENDING-PAY", 1000);
        mockMvc.perform(get("/api/v1/orders/" + orderId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.order.status").value("PAID"))
                .andExpect(jsonPath("$.data.entitlements.length()").value(2))
                .andExpect(jsonPath("$.data.entitlements[0].grantedCount").value(2))
                .andExpect(jsonPath("$.data.entitlements[1].grantedCount").value(3));
        assertThat(jdbcTemplate.queryForObject("SELECT paid_allocated_fen FROM crm_order_item WHERE order_id=?",
                Long.class, orderId)).isZero();
    }

    @Test
    void configuredMultiProjectAllocationUsesIntegerWeightsInOrderItemJson() throws Exception {
        AuthCookies admin = login("admin");
        long skuId = createTwoProjectSku(admin, "IT-Q02-CONFIGURED", 100L, 200L);
        long customerId = createCustomer(admin, "IT-Q02-明确权重");
        long orderId = createOrder(admin, customerId, skuId, 1, 1000);

        JsonNode snapshot = orderSnapshot(orderId);
        assertThat(snapshot.path("allocationStatus").asText()).isEqualTo("CONFIGURED");
        assertThat(snapshot.path("paidAllocatedFen").asLong()).isEqualTo(1000);
        assertThat(snapshot.at("/projectSnapshots/0/allocationPriceFen").asLong()).isEqualTo(100);
        assertThat(snapshot.at("/projectSnapshots/0/paidAllocatedFen").asLong()).isEqualTo(333);
        assertThat(snapshot.at("/projectSnapshots/1/allocationPriceFen").asLong()).isEqualTo(200);
        assertThat(snapshot.at("/projectSnapshots/1/paidAllocatedFen").asLong()).isEqualTo(667);
        assertThat(jdbcTemplate.queryForObject("SELECT paid_allocated_fen FROM crm_order_item WHERE order_id=?",
                Long.class, orderId)).isEqualTo(1000);
    }

    @Test
    void financeCanConfirmButCannotWriteOrdersAndCustomerMobileStaysMasked() throws Exception {
        AuthCookies manager = login("manager1");
        AuthCookies finance = login("finance1");
        String customerResponse = mockMvc.perform(manager.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"IT-FINANCE-客户\",\"ownerStaffId\":2,\"mobile\":\"13911112222\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long customerId = objectMapper.readTree(customerResponse).at("/data/id").asLong();
        String financeView = mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(finance.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mobileMasked").value("139****2222"))
                .andExpect(jsonPath("$.data.mobile").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(financeView).doesNotContain("13911112222");

        Map<String, Object> body = Map.of("customerId", customerId, "receiverType", "PLATFORM", "orderTypeCode", "REGULAR",
                "campaignId", 1, "campaignBatchId", 1, "payableFen", 181100, "validTo", "2027-12-31",
                "items", List.of(Map.of("skuId", 301, "quantity", 1)));
        mockMvc.perform(finance.withAuth(post("/api/v1/orders"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order WHERE customer_id=?",
                Integer.class, customerId)).isZero();

        long orderId = createOrder(manager, customerId, 301, 1, 181100);
        submitOrder(manager, orderId);
        confirmPayment(finance, orderId, "IT-FINANCE-PAY", 181100);
        mockMvc.perform(get("/api/v1/orders/" + orderId).cookie(finance.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.order.status").value("PAID"))
                .andExpect(jsonPath("$.data.order.payments[0].confirmedBy").value(5));
    }

    @Test
    void crossTeamLeadConfirmIs404BeforeDraftStateCheckAndWritesNothing() throws Exception {
        AuthCookies manager = login("manager1");
        AuthCookies otherLead = login("lead2");
        long customerId = createCustomer(manager, "IT-LEAD2-越权", 2);
        long orderId = createOrder(manager, customerId, 301, 1, 181100);
        String key = "IT-LEAD2-DENIED";

        MockHttpServletRequestBuilder request = otherLead.withAuth(
                        post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(paymentJson("IT-LEAD2-PAY", 181100));
        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_order WHERE id=?", String.class, orderId))
                .isEqualTo("DRAFT");
        assertNoPaymentArtifacts(orderId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_idempotency_record WHERE scope_code='payment.confirm' AND idempotency_key=?",
                Integer.class, key)).isZero();
    }

    @Test
    void lateOutboxFailureRollsBackPaymentStatusEntitlementsLedgerAndIdempotency() throws Exception {
        AuthCookies admin = login("admin");
        long customerId = createCustomer(admin, "IT-TX-晚失败");
        long orderId = createOrder(admin, customerId, 301, 1, 181100);
        submitOrder(admin, orderId);
        CurrentUser actor = currentUserService.loadById(1).orElseThrow();
        PaymentConfirmRequest request = new PaymentConfirmRequest("IT-TX-ROLLBACK", "BANK_TRANSFER", 181100L,
                Instant.parse("2026-10-24T03:00:00Z"), "IT-LATE-FAILURE", null);
        String eventId = "order.paid.v1:" + orderId;
        String constraintName = "it_fail_outbox_" + orderId;
        jdbcTemplate.execute("ALTER TABLE crm_outbox_event ADD CONSTRAINT " + constraintName
                + " CHECK (event_id <> '" + eventId + "')");
        try {
            assertThatThrownBy(() -> paymentService.confirm(1L, actor, orderId, request, "IT-TX-ROLLBACK"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining(constraintName);
        } finally {
            jdbcTemplate.execute("ALTER TABLE crm_outbox_event DROP CHECK " + constraintName);
        }

        Map<String, Object> order = jdbcTemplate.queryForMap(
                "SELECT status,paid_fen,paid_at FROM crm_order WHERE id=?", orderId);
        assertThat(order.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(((Number) order.get("paid_fen")).longValue()).isZero();
        assertThat(order.get("paid_at")).isNull();
        assertNoPaymentArtifacts(orderId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_idempotency_record WHERE scope_code='payment.confirm' AND idempotency_key='IT-TX-ROLLBACK'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE action_code='PAYMENT_CONFIRM' AND target_id=?",
                Integer.class, Long.toString(orderId))).isZero();
    }

    private PreparedRedemption prepareRedemption(AuthCookies admin, AuthCookies operator, long customerId,
                                                 long entitlementId, int count, String key) throws Exception {
        String lifecycleKey = key + "-LIFECYCLE-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("customerId", customerId, "institutionId", 2,
                "scheduledAt", Instant.now().plusSeconds(86400).toString(),
                "items", List.of(Map.of("entitlementId", entitlementId, "count", count)));
        JsonNode created = data(admin.withAuth(post("/api/v1/appointments"))
                .header("Idempotency-Key", lifecycleKey + "-APPOINTMENT")
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
        long appointmentId = created.at("/appointment/id").asLong();
        long version = 0;
        for (String action : List.of("confirm", "check-in", "service-done")) {
            JsonNode detail = data(operator.withAuth(post("/api/v1/appointments/" + appointmentId + "/" + action))
                    .header("Idempotency-Key", lifecycleKey + "-" + action)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("version", version, "reason", "IT-" + action))));
            version = detail.at("/appointment/version").asLong();
        }
        return new PreparedRedemption(appointmentId, version, entitlementId, count,
                Instant.now().toString(), key + "-PROOF", key);
    }

    private JsonNode postRedemption(AuthCookies operator, PreparedRedemption prepared) throws Exception {
        return data(redemptionRequest(operator, prepared, prepared.count(), prepared.evidenceRef(), prepared.idempotencyKey()));
    }

    private MockHttpServletRequestBuilder redemptionRequest(AuthCookies operator, PreparedRedemption prepared,
                                                             int count, String evidenceRef, String key) throws Exception {
        Map<String, Object> body = Map.of("appointmentVersion", prepared.appointmentVersion(),
                "serviceAt", prepared.serviceAt(), "evidenceRef", evidenceRef,
                "lines", List.of(Map.of("entitlementId", prepared.entitlementId(), "count", count)));
        return operator.withAuth(post("/api/v1/appointments/" + prepared.appointmentId() + "/redemptions")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private record PreparedRedemption(long appointmentId, long appointmentVersion, long entitlementId, int count,
                                      String serviceAt, String evidenceRef, String idempotencyKey) {
    }

    private void activateTenCountSku(AuthCookies admin) throws Exception {
        String versionStatus = jdbcTemplate.queryForObject("SELECT status FROM crm_package_version WHERE id=303", String.class);
        if ("DRAFT".equals(versionStatus)) {
            mockMvc.perform(admin.withAuth(post("/api/v1/package-versions/303/activate"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                    .andExpect(status().isOk());
        }
        String skuStatus = jdbcTemplate.queryForObject("SELECT status FROM crm_sku WHERE id=303", String.class);
        if ("INACTIVE".equals(skuStatus)) {
            mockMvc.perform(admin.withAuth(post("/api/v1/skus/303/activate"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void entitlementSummaryCountsOnlyUsableServicesInCurrentCustomerScope() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");
        AuthCookies otherManager = login("manager2");
        long customerId = createCustomer(admin, "IT-SUMMARY-可用服务");
        long orderId = createOrder(admin, customerId, 301, 2, 362200);
        submitOrder(admin, orderId);
        confirmPayment(admin, orderId, "IT-SUMMARY-PAY-" + UUID.randomUUID(), 362200);

        mockMvc.perform(get("/api/v1/entitlements/summary?customerId=" + customerId).cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCount").value(6))
                .andExpect(jsonPath("$.data.customerCount").value(1));
        mockMvc.perform(get("/api/v1/entitlements/summary?customerId=" + customerId).cookie(otherManager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCount").value(0))
                .andExpect(jsonPath("$.data.customerCount").value(0));

        jdbcTemplate.update("UPDATE crm_entitlement SET status='EXPIRED',valid_to='2020-01-01' "
                + "WHERE tenant_id=1 AND customer_id=?", customerId);
        mockMvc.perform(get("/api/v1/entitlements/summary?customerId=" + customerId).cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCount").value(0))
                .andExpect(jsonPath("$.data.customerCount").value(0));
    }

    private long createCustomer(AuthCookies actor, String nickname) throws Exception {
        return createCustomer(actor, nickname, 2);
    }

    private long createCustomer(AuthCookies actor, String nickname, long ownerId) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\",\"ownerStaffId\":" + ownerId + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private long createTwoProjectSku(AuthCookies admin, String codePrefix,
                                     long firstAllocationPrice, long secondAllocationPrice) throws Exception {
        Map<String, Object> versionBody = Map.of("selectionMode", "USE_N", "choiceCount", 2,
                "projects", List.of(
                        Map.of("projectId", 101, "grantCount", 2, "allocationPriceFen", firstAllocationPrice),
                        Map.of("projectId", 102, "grantCount", 3, "allocationPriceFen", secondAllocationPrice)));
        JsonNode version = data(admin.withAuth(post("/api/v1/packages/201/versions")
                .contentType(MediaType.APPLICATION_JSON).content(json(versionBody))));
        long versionId = version.at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/package-versions/" + versionId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());

        String skuCode = codePrefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> skuBody = Map.of("skuCode", skuCode, "packageVersionId", versionId,
                "specName", "Q02集成测试", "salePriceFen", 1000, "institutionIds", List.of(1));
        JsonNode sku = data(admin.withAuth(post("/api/v1/skus")
                .contentType(MediaType.APPLICATION_JSON).content(json(skuBody))));
        long skuId = sku.at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/skus/" + skuId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        return skuId;
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private JsonNode orderSnapshot(long orderId) throws Exception {
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT entitlement_snapshot FROM crm_order_item WHERE order_id=?", String.class, orderId);
        return objectMapper.readTree(snapshot);
    }

    private Map<String, Object> orderPaymentFact(long orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT paid_fen,DATE_FORMAT(paid_at,'%Y-%m-%dT%H:%i:%sZ') AS paid_at FROM crm_order WHERE id=?", orderId);
    }

    private void assertPaymentFactUnchanged(long orderId, Map<String, Object> before) {
        assertThat(orderPaymentFact(orderId)).isEqualTo(before);
    }

    private void assertNoPaymentArtifacts(long orderId) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_payment_txn WHERE order_id=?",
                Integer.class, orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_entitlement WHERE order_item_id IN (SELECT id FROM crm_order_item WHERE order_id=?)",
                Integer.class, orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id IN (SELECT id FROM crm_entitlement WHERE order_item_id IN (SELECT id FROM crm_order_item WHERE order_id=?))",
                Integer.class, orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?",
                Integer.class, "order.paid.v1:" + orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_order_status_history WHERE order_id=? AND to_status='PAID'",
                Integer.class, orderId)).isZero();
    }

    private long createOrder(AuthCookies actor, long customerId, long skuId, int quantity, long payableFen) throws Exception {
        Map<String, Object> body = Map.of("customerId", customerId, "receiverType", "PLATFORM", "orderTypeCode", "REGULAR",
                "campaignId", 1, "campaignBatchId", 1, "payableFen", payableFen, "validTo", "2027-12-31",
                "items", List.of(Map.of("skuId", skuId, "quantity", quantity)));
        String response = mockMvc.perform(actor.withAuth(post("/api/v1/orders"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/order/order/id").asLong();
    }

    private void submitOrder(AuthCookies actor, long orderId) throws Exception {
        mockMvc.perform(actor.withAuth(post("/api/v1/orders/" + orderId + "/submit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.order.status").value("PENDING_PAYMENT"));
    }

    private void confirmPayment(AuthCookies actor, long orderId, String tradeNo, long amountFen) throws Exception {
        MockHttpServletRequestBuilder request = actor.withAuth(
                        post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", tradeNo)
                .contentType(MediaType.APPLICATION_JSON).content(paymentJson(tradeNo, amountFen));
        mockMvc.perform(request)
                .andExpect(status().isOk());
    }

    private String paymentJson(String tradeNo, long amountFen) {
        return paymentJson(tradeNo, amountFen, "DEV-LOCAL-PROOF");
    }

    private String paymentJson(String tradeNo, long amountFen, String evidenceRef) {
        return "{\"externalTradeNo\":\"" + tradeNo + "\",\"paymentChannel\":\"BANK_TRANSFER\","
                + "\"amountFen\":" + amountFen + ",\"paidAt\":\"2026-10-24T03:00:00Z\","
                + "\"evidenceRef\":\"" + evidenceRef + "\"}";
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
